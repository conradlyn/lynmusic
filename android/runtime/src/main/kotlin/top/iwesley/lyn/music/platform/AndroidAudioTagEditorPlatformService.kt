package top.iwesley.lyn.music.platform

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import top.iwesley.lyn.music.core.model.AudioTagEditorPlatformService
import top.iwesley.lyn.music.core.model.NavidromeLocatorRuntime
import top.iwesley.lyn.music.core.model.RemotePlaybackUrlCandidate
import top.iwesley.lyn.music.core.model.isCompleteArtworkPayload
import top.iwesley.lyn.music.core.model.normalizeArtworkLocator
import top.iwesley.lyn.music.core.model.parseEmbyCoverLocator
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleCoverLocator
import top.iwesley.lyn.music.domain.readRemotePlaybackUrlCandidateWithFallback

internal class AndroidAudioTagEditorPlatformService(
    activity: ComponentActivity,
) : AudioTagEditorPlatformService {
    private val context = activity.applicationContext
    private var artworkContinuation: ((Uri?) -> Unit)? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val continuation = artworkContinuation
        artworkContinuation = null
        continuation?.invoke(uri)
    }

    override suspend fun pickArtworkBytes(): Result<ByteArray?> = runCatching {
        val uri = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                artworkContinuation = { pickedUri ->
                    if (continuation.isActive) {
                        continuation.resume(pickedUri)
                    }
                }
                continuation.invokeOnCancellation {
                    artworkContinuation = null
                }
                picker.launch("image/*")
            }
        }
        if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                readBytes(context, uri)
            }
        }
    }

    override suspend fun loadArtworkBytes(locator: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            val rawTarget = normalizeArtworkLocator(locator)?.trim().orEmpty()
            if (rawTarget.isBlank()) return@runCatching null
            val remoteCoverCandidates = if (
                parseSubsonicCompatibleCoverLocator(rawTarget) != null ||
                parseEmbyCoverLocator(rawTarget) != null
            ) {
                NavidromeLocatorRuntime.resolveCoverArtUrlCandidates(rawTarget).orEmpty()
            } else {
                emptyList()
            }
            val target = remoteCoverCandidates.firstOrNull()?.value ?: rawTarget
            when {
                target.isBlank() -> null
                target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) ->
                    loadRemoteArtworkBytes(
                        remoteCoverCandidates.takeIf { it.isNotEmpty() }
                            ?: listOf(RemotePlaybackUrlCandidate(value = target)),
                    )

                target.startsWith("content://", ignoreCase = true) ->
                    readBytes(context, Uri.parse(target))

                else -> {
                    val file = resolveAndroidLocalTrackFile(target)
                        ?: error("无法读取封面文件。")
                    if (!file.exists()) {
                        error("封面文件不存在。")
                    }
                    file.readBytes()
                }
            }
        }
    }

    private suspend fun loadRemoteArtworkBytes(
        targets: List<RemotePlaybackUrlCandidate>,
    ): ByteArray? {
        val resolved = readRemotePlaybackUrlCandidateWithFallback(
            candidates = targets,
            read = { target -> URL(target.value).openStream().use { input -> input.readBytes() } },
            isValidPayload = ::isCompleteArtworkPayload,
        ) ?: return null
        NavidromeLocatorRuntime.markResolvedUrlSuccess(resolved.first)
        return resolved.second
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: error("无法读取所选封面。")
    }
}
