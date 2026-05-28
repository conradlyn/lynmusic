package top.iwesley.lyn.music.core.model

data class SystemPlaybackControlCallbacks(
    val play: suspend () -> Unit = {},
    val pause: suspend () -> Unit = {},
    val togglePlayPause: suspend () -> Unit = {},
    val skipNext: suspend () -> Unit = {},
    val skipPrevious: suspend () -> Unit = {},
    val seekTo: suspend (Long) -> Unit = {},
)

interface SystemPlaybackControlsPlatformService {
    fun bind(callbacks: SystemPlaybackControlCallbacks)
    suspend fun updateSnapshot(snapshot: PlaybackSnapshot)
    suspend fun close()
}

class CompositeSystemPlaybackControlsPlatformService(
    private val services: List<SystemPlaybackControlsPlatformService>,
) : SystemPlaybackControlsPlatformService {
    override fun bind(callbacks: SystemPlaybackControlCallbacks) {
        services.forEach { service -> service.bind(callbacks) }
    }

    override suspend fun updateSnapshot(snapshot: PlaybackSnapshot) {
        services.forEach { service -> service.updateSnapshot(snapshot) }
    }

    override suspend fun close() {
        services.forEach { service -> service.close() }
    }
}

object UnsupportedSystemPlaybackControlsPlatformService : SystemPlaybackControlsPlatformService {
    override fun bind(callbacks: SystemPlaybackControlCallbacks) = Unit

    override suspend fun updateSnapshot(snapshot: PlaybackSnapshot) = Unit

    override suspend fun close() = Unit
}
