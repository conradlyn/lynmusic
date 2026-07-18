package top.iwesley.lyn.music

import kotlin.test.Test
import kotlin.test.assertEquals
import top.iwesley.lyn.music.core.model.NonNavidromeAudioScanResult
import top.iwesley.lyn.music.platform.classifyJvmScannedAudioFile

class JvmAudioImportSupportTest {

    @Test
    fun `jvm import classification keeps ape importable`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
            classifyJvmScannedAudioFile("good.ape"),
        )
    }

    @Test
    fun `jvm import classification supports wma`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
            classifyJvmScannedAudioFile("good.wma"),
        )
    }

    @Test
    fun `jvm import classification supports ogg`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
            classifyJvmScannedAudioFile("good.ogg"),
        )
    }

    @Test
    fun `jvm import classification marks other extra scanned formats unsupported`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyJvmScannedAudioFile("bad.opus"),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyJvmScannedAudioFile("bad.aiff"),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyJvmScannedAudioFile("bad.aif"),
        )
    }
}
