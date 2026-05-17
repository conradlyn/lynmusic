package top.iwesley.lyn.music.data.repository

import androidx.room.Room
import io.ktor.http.parseUrl
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import top.iwesley.lyn.music.core.model.EmbyCredential
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildEmbySongLocator
import top.iwesley.lyn.music.data.db.ImportSourceEntity
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.data.db.buildLynMusicDatabase
import top.iwesley.lyn.music.domain.serializeEmbyCredential

class EmbyPlaybackStatsReporterTest {
    @Test
    fun `reports emby now playing and submitted plays`() = runTest {
        val database = createEmbyStatsDatabase()
        val httpClient = RecordingEmbyStatsHttpClient()
        val reporter = EmbyPlaybackStatsReporter(
            database = database,
            secureCredentialStore = EmbyStatsCredentialStore(
                mutableMapOf("emby-cred" to serializeEmbyCredential(EmbyCredential("user-1", "token"))),
            ),
            httpClient = httpClient,
        )

        try {
            seedEmbyStatsSource(database)

            reporter.reportNowPlaying(embyStatsTrack("song-1"), atMillis = 123L)
            reporter.submitPlay(embyStatsTrack("song-1"), atMillis = 456L)

            assertEquals(
                listOf(
                    "/emby/Users/user-1/PlayingItems/song-1",
                    "/emby/Users/user-1/PlayedItems/song-1",
                ),
                httpClient.requestPaths,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `ignores non emby tracks`() = runTest {
        val database = createEmbyStatsDatabase()
        val httpClient = RecordingEmbyStatsHttpClient()
        val reporter = EmbyPlaybackStatsReporter(
            database = database,
            secureCredentialStore = EmbyStatsCredentialStore(),
            httpClient = httpClient,
        )

        try {
            reporter.submitPlay(
                Track(
                    id = "track:local",
                    sourceId = "local",
                    title = "Local",
                    mediaLocator = "file:///music/local.mp3",
                    relativePath = "Local.mp3",
                ),
                atMillis = 456L,
            )

            assertEquals(emptyList(), httpClient.requestPaths)
        } finally {
            database.close()
        }
    }
}

private fun createEmbyStatsDatabase(): LynMusicDatabase {
    val path = Files.createTempFile("lynmusic-emby-stats", ".db")
    return buildLynMusicDatabase(
        Room.databaseBuilder<LynMusicDatabase>(name = path.absolutePathString()),
    )
}

private suspend fun seedEmbyStatsSource(database: LynMusicDatabase) {
    database.importSourceDao().upsert(
        ImportSourceEntity(
            id = "emby-source",
            type = "EMBY",
            label = "Emby",
            rootReference = "https://emby.example.com/emby",
            server = null,
            shareName = null,
            directoryPath = null,
            username = "demo",
            credentialKey = "emby-cred",
            allowInsecureTls = false,
            enabled = true,
            lastScannedAt = null,
            createdAt = 1L,
        ),
    )
}

private fun embyStatsTrack(itemId: String): Track {
    return Track(
        id = embyTrackIdFor("emby-source", itemId),
        sourceId = "emby-source",
        title = "Emby $itemId",
        mediaLocator = buildEmbySongLocator("emby-source", itemId),
        relativePath = "Emby $itemId.flac",
    )
}

private class RecordingEmbyStatsHttpClient : LyricsHttpClient {
    val requestPaths = mutableListOf<String>()

    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        requestPaths += requireNotNull(parseUrl(request.url)).encodedPath
        return Result.success(LyricsHttpResponse(statusCode = 200, body = ""))
    }
}

private class EmbyStatsCredentialStore(
    private val values: MutableMap<String, String> = linkedMapOf(),
) : SecureCredentialStore {
    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun get(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
