package top.iwesley.lyn.music.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.EXTERNAL_OPEN_SOURCE_ID
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildExternalOpenTrackId
import top.iwesley.lyn.music.core.model.debug
import top.iwesley.lyn.music.core.model.warn

object AndroidExternalAudioOpenSupport {
    fun isExternalAudioOpenIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_VIEW && externalAudioUris(intent).isNotEmpty()
    }

    suspend fun tracksFromIntent(
        context: Context,
        intent: Intent?,
        logger: DiagnosticLogger,
    ): List<Track> {
        if (intent?.action != Intent.ACTION_VIEW) return emptyList()
        val uris = externalAudioUris(intent)
        if (uris.isEmpty()) return emptyList()
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            uris.mapIndexedNotNull { index, uri ->
                grantReadAccessIfPossible(appContext, intent, uri, logger)
                runCatching {
                    uri.toExternalAudioTrack(
                        context = appContext,
                        index = index,
                        logger = logger,
                    )
                }.onFailure { throwable ->
                    logger.warn(EXTERNAL_AUDIO_OPEN_LOG_TAG) {
                        "build-track-failed uri=$uri reason=${throwable.message.orEmpty()}"
                    }
                }.getOrNull()
            }
        }
    }
}

private fun externalAudioUris(intent: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()
    intent.data?.let(uris::add)
    val clipData = intent.clipData
    if (clipData != null) {
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index)?.uri?.let(uris::add)
        }
    }
    return uris
        .filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
        .distinctBy { it.toString() }
}

private fun grantReadAccessIfPossible(
    context: Context,
    intent: Intent,
    uri: Uri,
    logger: DiagnosticLogger,
) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
    if ((intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.onSuccess {
        logger.debug(EXTERNAL_AUDIO_OPEN_LOG_TAG) {
            "persistable-read-grant uri=$uri"
        }
    }.onFailure { throwable ->
        logger.debug(EXTERNAL_AUDIO_OPEN_LOG_TAG) {
            "persistable-read-grant-skipped uri=$uri reason=${throwable.message.orEmpty()}"
        }
    }
}

private fun Uri.toExternalAudioTrack(
    context: Context,
    index: Int,
    logger: DiagnosticLogger,
): Track {
    val metadata = context.contentResolver.readExternalAudioMetadata(this)
    val displayName = metadata.displayName ?: fallbackDisplayName(index)
    val relativePath = displayName.takeIf { it.isNotBlank() } ?: toString()
    val artworkDirectory = File(context.cacheDir, "artwork")
    val candidate = AndroidAudioTagReader.readCandidate(
        context = context,
        uri = this,
        displayName = displayName,
        relativePath = relativePath,
        artworkDirectory = artworkDirectory,
        logger = logger,
        sizeBytes = metadata.sizeBytes,
        modifiedAt = metadata.modifiedAt,
    )
    val mediaLocator = candidate.mediaLocator.ifBlank { toString() }
    val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { "外部音频 ${index + 1}" }
    return Track(
        id = buildExternalOpenTrackId(mediaLocator, index),
        sourceId = EXTERNAL_OPEN_SOURCE_ID,
        title = candidate.title.takeIf { it.isNotBlank() } ?: fallbackTitle,
        artistName = candidate.artistName?.takeIf { it.isNotBlank() },
        albumTitle = candidate.albumTitle?.takeIf { it.isNotBlank() },
        durationMs = candidate.durationMs,
        trackNumber = candidate.trackNumber,
        discNumber = candidate.discNumber,
        mediaLocator = mediaLocator,
        relativePath = candidate.relativePath.ifBlank { relativePath },
        artworkLocator = candidate.artworkLocator,
        sizeBytes = candidate.sizeBytes,
        modifiedAt = candidate.modifiedAt,
        addedAt = System.currentTimeMillis(),
        bitDepth = candidate.bitDepth,
        samplingRate = candidate.samplingRate,
        bitRate = candidate.bitRate,
        channelCount = candidate.channelCount,
    )
}

private fun ContentResolver.readExternalAudioMetadata(uri: Uri): ExternalAudioUriMetadata {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val file = File(uri.path.orEmpty())
        return ExternalAudioUriMetadata(
            displayName = file.name.takeIf { it.isNotBlank() },
            sizeBytes = file.length().coerceAtLeast(0L),
            modifiedAt = file.lastModified().coerceAtLeast(0L),
        )
    }
    return runCatching {
        query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ExternalAudioUriMetadata()
            ExternalAudioUriMetadata(
                displayName = cursor.stringColumn(OpenableColumns.DISPLAY_NAME),
                sizeBytes = cursor.longColumn(OpenableColumns.SIZE) ?: 0L,
                modifiedAt = cursor.modifiedAtColumn(),
            )
        }
    }.getOrNull() ?: ExternalAudioUriMetadata()
}

private fun Cursor.stringColumn(name: String): String? {
    val index = getColumnIndex(name)
    if (index < 0 || isNull(index)) return null
    return getString(index)?.takeIf { it.isNotBlank() }
}

private fun Cursor.longColumn(name: String): Long? {
    val index = getColumnIndex(name)
    if (index < 0 || isNull(index)) return null
    return getLong(index).takeIf { it >= 0L }
}

private fun Cursor.modifiedAtColumn(): Long {
    longColumn(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let { return it }
    val mediaStoreModifiedAt = longColumn(MediaStore.MediaColumns.DATE_MODIFIED)
    return when {
        mediaStoreModifiedAt == null -> 0L
        mediaStoreModifiedAt < 10_000_000_000L -> mediaStoreModifiedAt * 1_000L
        else -> mediaStoreModifiedAt
    }
}

private fun Uri.fallbackDisplayName(index: Int): String {
    return lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "external-audio-${index + 1}"
}

private data class ExternalAudioUriMetadata(
    val displayName: String? = null,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
)

private const val EXTERNAL_AUDIO_OPEN_LOG_TAG = "ExternalAudioOpen"
