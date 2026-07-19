package top.iwesley.lyn.music.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TvArtworkRequestPolicyTest {
    @Test
    fun playerArtworkUsesStableKeyForForegroundAndBackground() {
        val foreground = tvPlayerArtworkMemoryCacheKey("track:seven", 8L)
        val background = tvPlayerArtworkMemoryCacheKey("track:seven", 8L)

        assertEquals(foreground, background)
        assertEquals("tv-player-artwork:track:seven:8:1280", foreground)
    }

    @Test
    fun artworkVersionChangesMemoryCacheKey() {
        val previous = tvPlayerArtworkMemoryCacheKey("track:seven", 8L)
        val updated = tvPlayerArtworkMemoryCacheKey("track:seven", 9L)

        assertNotEquals(previous, updated)
    }

    @Test
    fun requestSizesAndMissingIdentityAreExplicit() {
        assertEquals(1280, TV_PLAYER_ARTWORK_MAX_DECODE_SIZE_PX)
        assertEquals(512, TV_LYRICS_SEARCH_ARTWORK_MAX_DECODE_SIZE_PX)
        assertNull(tvPlayerArtworkMemoryCacheKey("  ", 0L))
        assertNull(tvLyricsSearchArtworkMemoryCacheKey(null))
    }

    @Test
    fun previousTrackResolutionIsRejectedAfterTrackSwitch() {
        val previousRequest = TvPlayerArtworkRequestToken(
            cacheKey = "track-a",
            artworkLocator = "file:///artwork-a.jpg",
            cacheVersion = 1L,
        )
        val currentRequest = TvPlayerArtworkRequestToken(
            cacheKey = "track-b",
            artworkLocator = "file:///artwork-b.jpg",
            cacheVersion = 1L,
        )
        val previousResolution = TvPlayerArtworkResolution(
            requestToken = previousRequest,
            target = "/cache/artwork-a.jpg",
        )

        assertNull(previousResolution.targetFor(currentRequest))
        assertEquals("/cache/artwork-a.jpg", previousResolution.targetFor(previousRequest))
        assertNotEquals(previousRequest.memoryCacheIdentity(), currentRequest.memoryCacheIdentity())
    }

    @Test
    fun locatorChangeInvalidatesMemoryCacheEvenWhenTrackAndVersionMatch() {
        val previous = TvPlayerArtworkRequestToken("track-a", "file:///old.jpg", 1L)
        val updated = TvPlayerArtworkRequestToken("track-a", "file:///new.jpg", 1L)

        assertNotEquals(previous.memoryCacheIdentity(), updated.memoryCacheIdentity())
    }

    @Test
    fun previousLyricsSearchResolutionIsRejectedAfterCandidateSwitch() {
        val previousRequest = TvLyricsSearchArtworkRequestToken("https://example.com/artwork-a.jpg")
        val currentRequest = TvLyricsSearchArtworkRequestToken("https://example.com/artwork-b.jpg")
        val previousResolution = TvLyricsSearchArtworkResolution(
            requestToken = previousRequest,
            target = "/cache/artwork-a.jpg",
        )

        assertNull(previousResolution.targetFor(currentRequest))
        assertEquals("/cache/artwork-a.jpg", previousResolution.targetFor(previousRequest))
        assertNotEquals(
            tvLyricsSearchArtworkMemoryCacheKey(previousRequest.artworkLocator),
            tvLyricsSearchArtworkMemoryCacheKey(currentRequest.artworkLocator),
        )
    }

    @Test
    fun missingLyricsSearchArtworkNeverAcceptsPreviousResolution() {
        val previousResolution = TvLyricsSearchArtworkResolution(
            requestToken = TvLyricsSearchArtworkRequestToken("https://example.com/artwork-a.jpg"),
            target = "/cache/artwork-a.jpg",
        )

        assertNull(previousResolution.targetFor(TvLyricsSearchArtworkRequestToken(null)))
    }
}
