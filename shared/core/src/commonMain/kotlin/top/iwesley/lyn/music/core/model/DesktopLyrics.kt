package top.iwesley.lyn.music.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface DesktopLyricsPlatformService {
    val isSupported: Boolean
    val consumesAppLyricsUpdates: Boolean
    val closeRequests: Flow<Unit>

    fun hasOverlayPermission(): Boolean
    suspend fun requestOverlayPermission(): Boolean
    suspend fun setDesktopLyricsEnabled(enabled: Boolean)
    suspend fun updateLyrics(text: String)
    suspend fun hideLyrics()
    suspend fun release()
}

object UnsupportedDesktopLyricsPlatformService : DesktopLyricsPlatformService {
    override val isSupported: Boolean = false
    override val consumesAppLyricsUpdates: Boolean = false
    override val closeRequests: Flow<Unit> = emptyFlow()

    override fun hasOverlayPermission(): Boolean = false

    override suspend fun requestOverlayPermission(): Boolean = false

    override suspend fun setDesktopLyricsEnabled(enabled: Boolean) = Unit

    override suspend fun updateLyrics(text: String) = Unit

    override suspend fun hideLyrics() = Unit

    override suspend fun release() = Unit
}
