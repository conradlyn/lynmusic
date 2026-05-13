package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class JvmDesktopLyricsPlatformServiceTest {
    @Test
    fun `jvm service consumes app lyrics updates`() {
        val service = JvmDesktopLyricsPlatformService(RecordingDesktopLyricsWindowAdapter())

        assertTrue(service.consumesAppLyricsUpdates)
    }

    @Test
    fun `update and hide delegate to window adapter`() = runTest {
        val adapter = RecordingDesktopLyricsWindowAdapter()
        val service = JvmDesktopLyricsPlatformService(adapter)

        service.updateLyrics("当前歌词")
        service.hideLyrics()
        service.release()

        assertEquals(listOf("当前歌词"), adapter.shownTexts)
        assertEquals(1, adapter.hideCalls)
        assertEquals(1, adapter.releaseCalls)
    }

    @Test
    fun `disabling desktop lyrics hides window`() = runTest {
        val adapter = RecordingDesktopLyricsWindowAdapter()
        val service = JvmDesktopLyricsPlatformService(adapter)

        service.setDesktopLyricsEnabled(false)

        assertEquals(1, adapter.hideCalls)
    }

    @Test
    fun `close request from window is exposed as service flow`() = runTest {
        val adapter = RecordingDesktopLyricsWindowAdapter()
        val service = JvmDesktopLyricsPlatformService(adapter)
        val request = async(start = CoroutineStart.UNDISPATCHED) { service.closeRequests.first() }

        adapter.requestClose()

        assertEquals(Unit, request.await())
    }
}

private class RecordingDesktopLyricsWindowAdapter : JvmDesktopLyricsOverlayWindowAdapter {
    val shownTexts = mutableListOf<String>()
    var hideCalls = 0
    var releaseCalls = 0
    private var closeRequestHandler: (() -> Unit)? = null

    override fun showText(text: String) {
        shownTexts += text
    }

    override fun hide() {
        hideCalls += 1
    }

    override fun release() {
        releaseCalls += 1
    }

    override fun setCloseRequestHandler(handler: () -> Unit) {
        closeRequestHandler = handler
    }

    fun requestClose() {
        closeRequestHandler?.invoke()
    }
}
