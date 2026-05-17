package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmRemotePlaybackFallbackPolicyTest {
    @Test
    fun `does not fallback for playback errors without network indicators`() {
        assertFalse(isJvmRemotePlaybackFallbackAllowed("codec unsupported"))
        assertFalse(isJvmRemotePlaybackFallbackAllowed("media format not recognized"))
        assertFalse(isJvmRemotePlaybackFallbackAllowed("decoder failed"))
    }

    @Test
    fun `fallbacks only for retryable network or server failures`() {
        assertTrue(isJvmRemotePlaybackFallbackAllowed("HTTP 500"))
        assertTrue(isJvmRemotePlaybackFallbackAllowed("request timed out"))
        assertTrue(isJvmRemotePlaybackFallbackAllowed("connection reset"))
        assertFalse(isJvmRemotePlaybackFallbackAllowed("HTTP 401"))
        assertFalse(isJvmRemotePlaybackFallbackAllowed("HTTP 404"))
    }
}
