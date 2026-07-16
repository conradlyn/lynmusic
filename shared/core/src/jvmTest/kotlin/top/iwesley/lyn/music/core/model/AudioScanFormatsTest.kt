package top.iwesley.lyn.music.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioScanFormatsTest {

    private val androidSupportedImportExtensions = setOf(
        "mp3",
        "m4a",
        "aac",
        "wav",
        "flac",
    )

    @Test
    fun `recognizes non navidrome scannable audio extensions`() {
        assertTrue(isNonNavidromeScannableAudioFile("song.ogg"))
        assertTrue(isNonNavidromeScannableAudioFile("song.AIFF"))
        assertTrue(isNonNavidromeScannableAudioFile("song.ape"))
        assertFalse(isNonNavidromeScannableAudioFile("song.txt"))
        assertFalse(isNonNavidromeScannableAudioFile("song"))
    }

    @Test
    fun `android import classification keeps only supported formats importable`() {
        listOf("mp3", "m4a", "aac", "wav", "flac").forEach { extension ->
            assertEquals(
                NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
                classifyNonNavidromeAudioFile("good.$extension", androidSupportedImportExtensions),
            )
        }
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
            classifyNonNavidromeAudioFile("good.flac", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.ape", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.ogg", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.opus", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.wma", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.aiff", androidSupportedImportExtensions),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyNonNavidromeAudioFile("bad.aif", androidSupportedImportExtensions),
        )
    }

    @Test
    fun `scan counting keeps unsupported audio as failure`() {
        val files = listOf("good.flac", "bad.ogg")
        val failures = mutableListOf<ImportScanFailure>()
        var discoveredAudioFileCount = 0
        var importedTrackCount = 0

        files.forEach { fileName ->
            when (classifyNonNavidromeAudioFile(fileName, androidSupportedImportExtensions)) {
                NonNavidromeAudioScanResult.NOT_AUDIO -> Unit
                NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED -> {
                    discoveredAudioFileCount += 1
                    failures += unsupportedAudioImportFailure(fileName)
                }

                NonNavidromeAudioScanResult.IMPORT_SUPPORTED -> {
                    discoveredAudioFileCount += 1
                    importedTrackCount += 1
                }
            }
        }

        assertEquals(2, discoveredAudioFileCount)
        assertEquals(1, importedTrackCount)
        assertEquals(listOf("bad.ogg"), failures.map { it.relativePath })
        assertEquals(listOf(UNSUPPORTED_AUDIO_IMPORT_REASON), failures.map { it.reason })
    }
}
