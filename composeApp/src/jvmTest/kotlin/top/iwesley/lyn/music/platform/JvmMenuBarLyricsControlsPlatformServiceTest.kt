package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.SystemPlaybackControlCallbacks
import top.iwesley.lyn.music.core.model.Track

@OptIn(ExperimentalCoroutinesApi::class)
class JvmMenuBarLyricsControlsPlatformServiceTest {

    @Test
    fun `builds playback state from snapshot`() {
        val state = buildJvmMenuBarPlaybackState(
            PlaybackSnapshot(
                queue = listOf(track(id = "1"), track(id = "2")),
                currentIndex = 0,
                isPlaying = true,
            ),
        )

        assertEquals("1", state.trackId)
        assertTrue(state.isPlaying)
        assertTrue(state.hasPrevious)
        assertTrue(state.hasNext)
    }

    @Test
    fun `state has no track without current item`() {
        val state = buildJvmMenuBarPlaybackState(PlaybackSnapshot())

        assertFalse(state.hasTrack)
    }

    @Test
    fun `service shows menu bar when enabled with track and lyrics`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = this,
        )

        service.updateSnapshot(
            PlaybackSnapshot(
                queue = listOf(track(id = "track-1")),
                currentIndex = 0,
                isPlaying = true,
            ),
        )
        service.updateLyrics("第一句歌词")
        service.setEnabled(true)

        assertEquals(true, bridge.enabledCalls.last())
        assertEquals("第一句歌词", bridge.lyricsUpdates.last())
        assertEquals(
            JvmMenuBarPlaybackState(
                trackId = "track-1",
                isPlaying = true,
                hasPrevious = false,
                hasNext = false,
            ),
            bridge.playbackStates.last(),
        )
    }

    @Test
    fun `service keeps menu bar enabled when lyrics become blank`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = this,
        )
        service.updateSnapshot(
            PlaybackSnapshot(
                queue = listOf(track(id = "track-1")),
                currentIndex = 0,
            ),
        )
        service.updateLyrics("line")
        service.setEnabled(true)

        service.updateLyrics(" ")

        assertEquals(true, bridge.enabledCalls.last())
        assertEquals(null, bridge.lyricsUpdates.last())
    }

    @Test
    fun `service keeps menu bar enabled and disables controls when current track is cleared`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = this,
        )
        service.updateSnapshot(
            PlaybackSnapshot(
                queue = listOf(track(id = "track-1")),
                currentIndex = 0,
            ),
        )
        service.updateLyrics("track one lyric")
        service.setEnabled(true)

        service.updateSnapshot(
            PlaybackSnapshot(
                queue = emptyList(),
                currentIndex = -1,
            ),
        )

        assertEquals(true, bridge.enabledCalls.last())
        assertFalse(bridge.playbackStates.last().hasTrack)
    }

    @Test
    fun `service skips native playback update when menu bar state is unchanged`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = this,
        )
        service.setEnabled(true)
        bridge.playbackStates.clear()

        service.updateSnapshot(
            PlaybackSnapshot(
                queue = listOf(track(id = "track-1")),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 1_000L,
            ),
        )
        service.updateSnapshot(
            PlaybackSnapshot(
                queue = listOf(track(id = "track-1")),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 2_000L,
            ),
        )

        assertEquals(1, bridge.playbackStates.size)
    }

    @Test
    fun `service forwards menu bar command callbacks`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val events = mutableListOf<String>()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        service.bind(
            SystemPlaybackControlCallbacks(
                skipPrevious = { events += "previous" },
                togglePlayPause = { events += "toggle" },
                skipNext = { events += "next" },
            ),
        )

        bridge.emit(MacOsMenuBarCommand.Previous)
        bridge.emit(MacOsMenuBarCommand.TogglePlayPause)
        bridge.emit(MacOsMenuBarCommand.Next)
        advanceUntilIdle()

        assertEquals(listOf("previous", "toggle", "next"), events)
    }

    @Test
    fun `service close hides and disposes bridge`() = runTest {
        val bridge = RecordingMenuBarBridge()
        val service = JvmMenuBarLyricsControlsPlatformService(
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )

        service.close()

        assertEquals(false, bridge.enabledCalls.last())
        assertTrue(bridge.disposed)
    }

    @Test
    fun `factory reports unsupported outside macos`() {
        val registration = createJvmMenuBarLyricsControlsPlatformService(osName = "Linux")

        assertFalse(registration.isSupported)
    }

    @Test
    fun `factory reports unsupported when bridge load fails`() {
        val registration = createJvmMenuBarLyricsControlsPlatformService(
            osName = "Mac OS X",
            bridgeLoader = { error("missing native bridge") },
        )

        assertFalse(registration.isSupported)
    }

    private class RecordingMenuBarBridge : MacOsMenuBarLyricsControlsBridge {
        val enabledCalls = mutableListOf<Boolean>()
        val lyricsUpdates = mutableListOf<String?>()
        val playbackStates = mutableListOf<JvmMenuBarPlaybackState>()
        var disposed = false
            private set
        private var handler: (MacOsMenuBarCommand) -> Unit = {}

        override fun setCommandHandler(handler: (MacOsMenuBarCommand) -> Unit) {
            this.handler = handler
        }

        override fun setEnabled(enabled: Boolean) {
            enabledCalls += enabled
        }

        override fun updateLyrics(text: String?) {
            lyricsUpdates += text
        }

        override fun updatePlaybackState(state: JvmMenuBarPlaybackState) {
            playbackStates += state
        }

        override fun dispose() {
            disposed = true
        }

        fun emit(command: MacOsMenuBarCommand) {
            handler(command)
        }
    }
}

private fun track(
    id: String = "track-1",
    title: String = "Song Title",
): Track {
    return Track(
        id = id,
        sourceId = "source-1",
        title = title,
        artistName = "Artist",
        albumTitle = "Album",
        durationMs = 60_000,
        mediaLocator = "file:///tmp/$id.mp3",
        relativePath = "$id.mp3",
    )
}
