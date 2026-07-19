package top.iwesley.lyn.music.tv

internal class ProcessComponentStore<T : Any>(
    private val factory: () -> T,
    private val onCreated: (Result<T>) -> Unit = {},
) {
    private val lock = Any()

    @Volatile
    private var component: T? = null

    fun getOrCreate(): Result<T> {
        component?.let { return Result.success(it) }
        return synchronized(lock) {
            component?.let { Result.success(it) } ?: runCatching(factory).also { attempt ->
                attempt.onSuccess { created ->
                    component = created
                }
                onCreated(attempt)
            }
        }
    }

    fun clear(dispose: (T) -> Unit = {}) {
        val created = synchronized(lock) {
            val current = component
            component = null
            current
        }
        created?.let(dispose)
    }
}
