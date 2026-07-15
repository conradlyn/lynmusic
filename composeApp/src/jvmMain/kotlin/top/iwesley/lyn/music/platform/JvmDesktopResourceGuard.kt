package top.iwesley.lyn.music.platform

internal class JvmDesktopResourceGuard {
    private val lock = Any()
    private val closeActions = linkedMapOf<Token, suspend () -> Unit>()
    private var closed = false

    fun register(closeAction: suspend () -> Unit): Token = synchronized(lock) {
        check(!closed) { "资源守卫已经关闭。" }
        val token = Token()
        closeActions[token] = closeAction
        token
    }

    fun transfer(token: Token) {
        synchronized(lock) {
            closeActions.remove(token)
        }
    }

    suspend fun closeAll(): Result<Unit> {
        val actions = synchronized(lock) {
            if (closed) return Result.success(Unit)
            closed = true
            closeActions.values.toList().asReversed().also { closeActions.clear() }
        }
        val failures = mutableListOf<Throwable>()
        actions.forEach { action ->
            runCatching { action() }.onFailure(failures::add)
        }
        val primary = failures.firstOrNull() ?: return Result.success(Unit)
        failures.drop(1).forEach { failure -> primary.addSuppressedSafely(failure) }
        return Result.failure(primary)
    }

    fun closeAllBlocking(): Result<Unit> = kotlinx.coroutines.runBlocking { closeAll() }

    class Token internal constructor()
}

internal fun Throwable.addSuppressedSafely(failure: Throwable) {
    if (failure === this || suppressedExceptions.any { suppressed -> suppressed === failure }) return
    runCatching { addSuppressed(failure) }
}
