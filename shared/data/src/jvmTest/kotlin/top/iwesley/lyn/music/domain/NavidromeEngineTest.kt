package top.iwesley.lyn.music.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NavidromeSourceDraft

class NavidromeEngineTest {
    @Test
    fun `probe library supports online paging when native page omits total count`() = runTest {
        val probe = probeNavidromeLibrary(
            draft = navidromeDraft(),
            httpClient = ProbeNavidromeHttpClient(totalTrackCountHeader = null),
        )

        assertNull(probe.totalTrackCount)
        assertTrue(probe.supportsOnlineLibraryPaging)
    }

    @Test
    fun `probe library uses native total count header`() = runTest {
        val probe = probeNavidromeLibrary(
            draft = navidromeDraft(),
            httpClient = ProbeNavidromeHttpClient(totalTrackCountHeader = "50000"),
        )

        assertEquals(50_000, probe.totalTrackCount)
        assertTrue(probe.supportsOnlineLibraryPaging)
    }

    @Test
    fun `probe library rejects legacy navidrome for online paging`() = runTest {
        val probe = probeNavidromeLibrary(
            draft = navidromeDraft(),
            httpClient = ProbeNavidromeHttpClient(
                totalTrackCountHeader = null,
                serverVersion = "0.43.0",
            ),
        )

        assertNull(probe.totalTrackCount)
        assertFalse(probe.supportsOnlineLibraryPaging)
    }

    @Test
    fun `probe library rejects incompatible native song page for online paging`() = runTest {
        val probe = probeNavidromeLibrary(
            draft = navidromeDraft(),
            httpClient = ProbeNavidromeHttpClient(
                totalTrackCountHeader = null,
                nativeSongStatusCode = 404,
            ),
        )

        assertNull(probe.totalTrackCount)
        assertFalse(probe.supportsOnlineLibraryPaging)
    }
}

private class ProbeNavidromeHttpClient(
    private val totalTrackCountHeader: String?,
    private val serverVersion: String = "0.55.0",
    private val nativeSongStatusCode: Int = 200,
) : LyricsHttpClient {
    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        return Result.success(
            when {
                request.url.contains("/rest/ping") -> LyricsHttpResponse(
                    statusCode = 200,
                    body = """
                        {
	                          "subsonic-response": {
	                            "status": "ok",
	                            "type": "navidrome",
	                            "serverVersion": "$serverVersion"
	                          }
	                        }
                    """.trimIndent(),
                )

                request.url.endsWith("/auth/login") -> LyricsHttpResponse(
                    statusCode = 200,
                    body = """{"token":"native-token"}""",
                )

                request.url.contains("/api/song") -> LyricsHttpResponse(
                    statusCode = nativeSongStatusCode,
                    body = """[{"id":"song-1","title":"Song","path":"Music/Song.flac"}]""",
                    headers = totalTrackCountHeader?.let { mapOf("X-Total-Count" to it) }.orEmpty(),
                )

                else -> error("Unexpected request: ${request.url}")
            },
        )
    }
}

private fun navidromeDraft(): NavidromeSourceDraft {
    return NavidromeSourceDraft(
        label = "Navidrome",
        baseUrl = "https://navidrome.example",
        username = "user",
        password = "password",
    )
}
