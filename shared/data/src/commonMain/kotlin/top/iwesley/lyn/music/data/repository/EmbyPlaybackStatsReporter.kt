package top.iwesley.lyn.music.data.repository

import kotlinx.coroutines.CancellationException
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.PlaybackStatsReporter
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.info
import top.iwesley.lyn.music.core.model.parseEmbySongLocator
import top.iwesley.lyn.music.core.model.warn
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.domain.reportEmbyNowPlaying
import top.iwesley.lyn.music.domain.RemoteSourceAddressSelector
import top.iwesley.lyn.music.domain.resolveEmbySource
import top.iwesley.lyn.music.domain.submitEmbyPlay

class EmbyPlaybackStatsReporter(
    private val database: LynMusicDatabase,
    private val secureCredentialStore: SecureCredentialStore,
    private val httpClient: LyricsHttpClient,
    private val logger: DiagnosticLogger = NoopDiagnosticLogger,
    private val addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
) : PlaybackStatsReporter {
    override suspend fun reportNowPlaying(track: Track, atMillis: Long) {
        report(track = track, submission = false)
    }

    override suspend fun submitPlay(track: Track, atMillis: Long) {
        report(track = track, submission = true)
    }

    private suspend fun report(track: Track, submission: Boolean) {
        val target = resolveEmbyStatsTarget(track) ?: return
        runCatching {
            if (submission) {
                submitEmbyPlay(
                    httpClient = httpClient,
                    source = target.source,
                    itemId = target.itemId,
                    logger = logger,
                )
            } else {
                reportEmbyNowPlaying(
                    httpClient = httpClient,
                    source = target.source,
                    itemId = target.itemId,
                    logger = logger,
                )
            }
        }.onSuccess {
            logger.info(EMBY_STATS_LOG_TAG) {
                "report-complete source=${track.sourceId} item=${target.itemId} submission=$submission"
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            logger.warn(EMBY_STATS_LOG_TAG) {
                "report-failed source=${track.sourceId} item=${target.itemId} " +
                    "submission=$submission cause=${throwable.message.orEmpty()}"
            }
        }
    }

    private suspend fun resolveEmbyStatsTarget(track: Track): EmbyStatsTarget? {
        val parsed = parseEmbySongLocator(track.mediaLocator) ?: return null
        if (parsed.first != track.sourceId) return null
        val source = resolveEmbySource(database, secureCredentialStore, parsed.first, addressSelector) ?: return null
        return EmbyStatsTarget(
            source = source,
            itemId = parsed.second,
        )
    }
}

private data class EmbyStatsTarget(
    val source: top.iwesley.lyn.music.domain.EmbyResolvedSource,
    val itemId: String,
)

private const val EMBY_STATS_LOG_TAG = "EmbyStats"
