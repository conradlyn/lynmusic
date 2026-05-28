package top.iwesley.lyn.music.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.MenuBarLyricsControlsPlatformService
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.SystemPlaybackControlCallbacks
import top.iwesley.lyn.music.core.model.UnsupportedMenuBarLyricsControlsPlatformService
import top.iwesley.lyn.music.core.model.warn

internal data class JvmMenuBarLyricsControlsPlatformServiceRegistration(
    val service: MenuBarLyricsControlsPlatformService,
    val isSupported: Boolean,
)

internal fun createJvmMenuBarLyricsControlsPlatformService(
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    osName: String = System.getProperty("os.name").orEmpty(),
    bridgeLoader: () -> MacOsMenuBarLyricsControlsBridge = { JnaMacOsMenuBarLyricsControlsBridge.load() },
): JvmMenuBarLyricsControlsPlatformServiceRegistration {
    if (!isMacOsMenuBarName(osName)) {
        return JvmMenuBarLyricsControlsPlatformServiceRegistration(
            service = UnsupportedMenuBarLyricsControlsPlatformService,
            isSupported = false,
        )
    }
    val bridge = runCatching { bridgeLoader() }
        .onFailure { throwable ->
            logger.warn(JVM_MENU_BAR_LYRICS_CONTROLS_TAG) {
                "macOS menu bar lyrics bridge unavailable: ${throwable.message.orEmpty()}"
            }
        }
        .getOrNull()
        ?: return JvmMenuBarLyricsControlsPlatformServiceRegistration(
            service = UnsupportedMenuBarLyricsControlsPlatformService,
            isSupported = false,
        )
    return JvmMenuBarLyricsControlsPlatformServiceRegistration(
        service = JvmMenuBarLyricsControlsPlatformService(bridge = bridge),
        isSupported = true,
    )
}

internal data class JvmMenuBarPlaybackState(
    val trackId: String?,
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
) {
    val hasTrack: Boolean = trackId != null
}

internal fun buildJvmMenuBarPlaybackState(snapshot: PlaybackSnapshot): JvmMenuBarPlaybackState {
    val hasQueueNavigation = snapshot.queue.size > 1
    return JvmMenuBarPlaybackState(
        trackId = snapshot.currentTrack?.id,
        isPlaying = snapshot.isPlaying,
        hasPrevious = hasQueueNavigation,
        hasNext = hasQueueNavigation,
    )
}

internal class JvmMenuBarLyricsControlsPlatformService(
    private val bridge: MacOsMenuBarLyricsControlsBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MenuBarLyricsControlsPlatformService {
    override val isSupported: Boolean = true

    private var callbacks = SystemPlaybackControlCallbacks()
    private var latestEnabled = false
    private var latestLyrics: String? = null
    private var latestPlaybackState = JvmMenuBarPlaybackState(
        trackId = null,
        isPlaying = false,
        hasPrevious = false,
        hasNext = false,
    )
    private var isClosed = false

    init {
        bridge.setCommandHandler { command ->
            when (command) {
                MacOsMenuBarCommand.Previous -> scope.launch { callbacks.skipPrevious() }
                MacOsMenuBarCommand.TogglePlayPause -> scope.launch { callbacks.togglePlayPause() }
                MacOsMenuBarCommand.Next -> scope.launch { callbacks.skipNext() }
            }
        }
    }

    override fun bind(callbacks: SystemPlaybackControlCallbacks) {
        this.callbacks = callbacks
    }

    override suspend fun setEnabled(enabled: Boolean) {
        if (isClosed) return
        if (latestEnabled == enabled) return
        latestEnabled = enabled
        bridge.setEnabled(enabled)
        if (enabled) {
            bridge.updateLyrics(latestLyrics)
            bridge.updatePlaybackState(latestPlaybackState)
        }
    }

    override suspend fun updateLyrics(text: String?) {
        if (isClosed) return
        val nextLyrics = text?.trim()?.takeIf { it.isNotBlank() }
        if (latestLyrics == nextLyrics) return
        latestLyrics = nextLyrics
        if (latestEnabled) {
            bridge.updateLyrics(latestLyrics)
        }
    }

    override suspend fun updateSnapshot(snapshot: PlaybackSnapshot) {
        if (isClosed) return
        val nextState = buildJvmMenuBarPlaybackState(snapshot)
        if (latestPlaybackState == nextState) return
        latestPlaybackState = nextState
        if (latestEnabled) {
            bridge.updatePlaybackState(latestPlaybackState)
        }
    }

    override suspend fun close() {
        if (isClosed) return
        isClosed = true
        bridge.setEnabled(false)
        bridge.dispose()
        scope.cancel()
    }
}

internal enum class MacOsMenuBarCommand {
    Previous,
    TogglePlayPause,
    Next,
}

internal interface MacOsMenuBarLyricsControlsBridge {
    fun setCommandHandler(handler: (MacOsMenuBarCommand) -> Unit)
    fun setEnabled(enabled: Boolean)
    fun updateLyrics(text: String?)
    fun updatePlaybackState(state: JvmMenuBarPlaybackState)
    fun dispose()
}

private class JnaMacOsMenuBarLyricsControlsBridge private constructor(
    private val nativeLibrary: LynMusicMenuBarNativeLibrary,
    private val handle: Pointer,
    @Suppress("unused")
    private val nativeCallback: LynMusicMenuBarCommandCallback,
    private val commandHandlerRef: AtomicReference<(MacOsMenuBarCommand) -> Unit>,
) : MacOsMenuBarLyricsControlsBridge {
    private var isDisposed = false

    override fun setCommandHandler(handler: (MacOsMenuBarCommand) -> Unit) {
        commandHandlerRef.set(handler)
    }

    override fun setEnabled(enabled: Boolean) {
        if (isDisposed) return
        nativeLibrary.lyn_music_menu_bar_set_enabled(handle, enabled.toMenuBarNativeInt())
    }

    override fun updateLyrics(text: String?) {
        if (isDisposed) return
        nativeLibrary.lyn_music_menu_bar_update_lyrics(handle, text)
    }

    override fun updatePlaybackState(state: JvmMenuBarPlaybackState) {
        if (isDisposed) return
        nativeLibrary.lyn_music_menu_bar_update_playback_state(
            handle,
            state.hasTrack.toMenuBarNativeInt(),
            state.isPlaying.toMenuBarNativeInt(),
            state.hasPrevious.toMenuBarNativeInt(),
            state.hasNext.toMenuBarNativeInt(),
        )
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        nativeLibrary.lyn_music_menu_bar_dispose(handle)
    }

    companion object {
        fun load(): JnaMacOsMenuBarLyricsControlsBridge {
            val nativeLibrary = Native.load(
                extractMacOsMenuBarBridgeLibrary().toAbsolutePath().toString(),
                LynMusicMenuBarNativeLibrary::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name()),
            )
            val commandHandlerRef = AtomicReference<(MacOsMenuBarCommand) -> Unit>({})
            val callback = LynMusicMenuBarCommandCallback { command ->
                decodeMacOsMenuBarCommand(command)?.let { decoded ->
                    commandHandlerRef.get().invoke(decoded)
                }
            }
            val handle = nativeLibrary.lyn_music_menu_bar_create(callback)
                ?: error("macOS menu bar lyrics bridge returned a null handle")
            return JnaMacOsMenuBarLyricsControlsBridge(nativeLibrary, handle, callback, commandHandlerRef)
        }
    }
}

private fun decodeMacOsMenuBarCommand(command: Int): MacOsMenuBarCommand? {
    return when (command) {
        NATIVE_MENU_BAR_COMMAND_PREVIOUS -> MacOsMenuBarCommand.Previous
        NATIVE_MENU_BAR_COMMAND_TOGGLE_PLAY_PAUSE -> MacOsMenuBarCommand.TogglePlayPause
        NATIVE_MENU_BAR_COMMAND_NEXT -> MacOsMenuBarCommand.Next
        else -> null
    }
}

private interface LynMusicMenuBarNativeLibrary : Library {
    fun lyn_music_menu_bar_create(callback: LynMusicMenuBarCommandCallback): Pointer?

    fun lyn_music_menu_bar_set_enabled(handle: Pointer, enabled: Int): Int

    fun lyn_music_menu_bar_update_lyrics(handle: Pointer, lyrics: String?): Int

    fun lyn_music_menu_bar_update_playback_state(
        handle: Pointer,
        hasTrack: Int,
        isPlaying: Int,
        hasPrevious: Int,
        hasNext: Int,
    ): Int

    fun lyn_music_menu_bar_dispose(handle: Pointer): Int
}

private fun interface LynMusicMenuBarCommandCallback : Callback {
    fun invoke(command: Int)
}

@OptIn(ExperimentalPathApi::class)
private fun extractMacOsMenuBarBridgeLibrary(): java.nio.file.Path {
    val resourceName = "/native/macos/libLynMusicNowPlayingBridge.dylib"
    val resource = JnaMacOsMenuBarLyricsControlsBridge::class.java.getResourceAsStream(resourceName)
        ?: error("Missing resource $resourceName")
    val directory = Files.createTempDirectory("lynmusic-menubar-")
    val target = directory.resolve("libLynMusicNowPlayingBridge.dylib")
    target.deleteIfExists()
    resource.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    directory.toFile().deleteOnExit()
    target.toFile().deleteOnExit()
    return target
}

private fun Boolean.toMenuBarNativeInt(): Int = if (this) 1 else 0

private fun isMacOsMenuBarName(osName: String): Boolean = osName.contains("mac", ignoreCase = true)

private const val JVM_MENU_BAR_LYRICS_CONTROLS_TAG = "JvmMenuBarLyricsControls"
private const val NATIVE_MENU_BAR_COMMAND_PREVIOUS = 1
private const val NATIVE_MENU_BAR_COMMAND_TOGGLE_PLAY_PAUSE = 2
private const val NATIVE_MENU_BAR_COMMAND_NEXT = 3
