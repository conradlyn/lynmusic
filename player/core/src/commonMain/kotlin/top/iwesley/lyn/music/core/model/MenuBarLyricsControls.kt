package top.iwesley.lyn.music.core.model

interface MenuBarLyricsControlsPlatformService : SystemPlaybackControlsPlatformService {
    val isSupported: Boolean

    suspend fun setEnabled(enabled: Boolean)
    suspend fun updateLyrics(text: String?)
}

object UnsupportedMenuBarLyricsControlsPlatformService : MenuBarLyricsControlsPlatformService {
    override val isSupported: Boolean = false

    override fun bind(callbacks: SystemPlaybackControlCallbacks) = Unit

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun updateLyrics(text: String?) = Unit

    override suspend fun updateSnapshot(snapshot: PlaybackSnapshot) = Unit

    override suspend fun close() = Unit
}
