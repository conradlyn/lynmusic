package top.iwesley.lyn.music

import kotlinx.coroutines.sync.Mutex

class StartupAutoOpenGate {
    private val mutex = Mutex()
    private var requestedOnStartup: Boolean? = null
    private var completed = false

    suspend fun runStartupHydration(
        requested: Boolean,
        hydrate: suspend (expandPlayerAfterHydration: Boolean) -> Unit,
    ) {
        mutex.lock()
        try {
            val initialRequest = requestedOnStartup ?: requested.also {
                requestedOnStartup = it
            }
            hydrate(initialRequest && !completed)
            completed = true
        } finally {
            mutex.unlock()
        }
    }
}
