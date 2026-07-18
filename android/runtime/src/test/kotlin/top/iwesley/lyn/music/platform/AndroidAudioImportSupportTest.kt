package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import top.iwesley.lyn.music.core.model.NonNavidromeAudioScanResult

class AndroidAudioImportSupportTest {

    @Test
    fun `android import classification supports ogg`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_SUPPORTED,
            classifyAndroidScannedAudioFile("good.ogg"),
        )
    }

    @Test
    fun `android import classification keeps other extra formats unsupported`() {
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyAndroidScannedAudioFile("bad.opus"),
        )
        assertEquals(
            NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
            classifyAndroidScannedAudioFile("bad.aiff"),
        )
    }
}
