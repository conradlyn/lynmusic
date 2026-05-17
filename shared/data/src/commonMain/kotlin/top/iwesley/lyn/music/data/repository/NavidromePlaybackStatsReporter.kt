package top.iwesley.lyn.music.data.repository

import kotlinx.coroutines.CancellationException
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.PlaybackStatsReporter
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.SubsonicAuthMode
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.info
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.core.model.warn
import top.iwesley.lyn.music.data.db.ImportSourceEntity
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.domain.NavidromeResolvedSource
import top.iwesley.lyn.music.domain.isSubsonicCompatibleSourceType
import top.iwesley.lyn.music.domain.normalizeSubsonicBaseUrl
import top.iwesley.lyn.music.domain.requestNavidromeJson
import top.iwesley.lyn.music.domain.toSubsonicAuthMode

class NavidromePlaybackStatsReporter(
    private val database: LynMusicDatabase,
    private val secureCredentialStore: SecureCredentialStore,
    private val httpClient: LyricsHttpClient,
    private val logger: DiagnosticLogger = NoopDiagnosticLogger,
) : PlaybackStatsReporter {

    override suspend fun reportNowPlaying(track: Track, atMillis: Long) {
        scrobble(track = track, submission = false, atMillis = atMillis)
    }

    override suspend fun submitPlay(track: Track, atMillis: Long) {
        scrobble(track = track, submission = true, atMillis = atMillis)
    }

    private suspend fun scrobble(track: Track, submission: Boolean, atMillis: Long) {
        val target = resolveNavidromeScrobbleTarget(track) ?: return
        runCatching {
            requestNavidromeJson(
                httpClient = httpClient,
                source = target.source,
                endpoint = "scrobble",
                parameters = mapOf(
                    "id" to target.songId,
                    "submission" to submission.toString(),
                    "time" to atMillis.coerceAtLeast(0L).toString(),
                ),
                logger = logger,
                logContext = "songId=\"${target.songId}\" submission=$submission track=${track.id}",
            )
        }.onSuccess {
            logger.info(NAVIDROME_STATS_LOG_TAG) {
                "scrobble-complete source=${track.sourceId} song=${target.songId} submission=$submission"
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            logger.warn(NAVIDROME_STATS_LOG_TAG) {
                "scrobble-failed source=${track.sourceId} song=${target.songId} " +
                    "submission=$submission cause=${throwable.message.orEmpty()}"
            }
        }
    }

    private suspend fun resolveNavidromeScrobbleTarget(track: Track): NavidromeScrobbleTarget? {
        val parsed = parseSubsonicCompatibleSongLocator(track.mediaLocator) ?: return null
        if (parsed.sourceId != track.sourceId) return null
        val source = database.importSourceDao().getById(parsed.sourceId)
            ?.takeIf { it.subsonicCompatibleSourceType() != null && it.enabled }
            ?: return null
        val resolvedSource = source.toSubsonicCompatibleResolvedSource() ?: return null
        return NavidromeScrobbleTarget(
            source = resolvedSource,
            songId = parsed.itemId,
        )
    }

    private suspend fun ImportSourceEntity.toSubsonicCompatibleResolvedSource(): NavidromeResolvedSource? {
        val sourceType = subsonicCompatibleSourceType() ?: return null
        val authMode = authMode.toSubsonicAuthMode()
        val username = username?.trim().orEmpty()
        val credential = credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
        if (authMode == SubsonicAuthMode.PASSWORD && (username.isBlank() || credential.isBlank())) return null
        if (authMode == SubsonicAuthMode.API_KEY && credential.isBlank()) return null
        return NavidromeResolvedSource(
            baseUrl = normalizeSubsonicBaseUrl(rootReference),
            username = username,
            password = credential,
            authMode = authMode,
            sourceType = sourceType,
        )
    }

    private fun ImportSourceEntity.subsonicCompatibleSourceType(): ImportSourceType? {
        val sourceType = runCatching { ImportSourceType.valueOf(type) }.getOrNull() ?: return null
        return sourceType.takeIf(::isSubsonicCompatibleSourceType)
    }
}

private data class NavidromeScrobbleTarget(
    val source: NavidromeResolvedSource,
    val songId: String,
)

private const val NAVIDROME_STATS_LOG_TAG = "NavidromeStats"
