package top.iwesley.lyn.music.core.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface Store<S, I, E> {
    val state: StateFlow<S>
    val effects: SharedFlow<E>
    fun dispatch(intent: I)
}

abstract class BaseStore<S, I, E>(
    initialState: S,
    private val scope: CoroutineScope,
) : Store<S, I, E> {
    private val mutableState = MutableStateFlow(initialState)
    private val mutableEffects = MutableSharedFlow<E>(extraBufferCapacity = 16)

    final override val state: StateFlow<S> = mutableState.asStateFlow()
    final override val effects: SharedFlow<E> = mutableEffects.asSharedFlow()

    final override fun dispatch(intent: I) {
        val intentToHandle = reduceStateImmediately(intent)
        val handlingJob = scope.launch {
            handleIntent(intentToHandle)
        }
        handlingJob.invokeOnCompletion {
            onIntentHandlingCompleted(intentToHandle)
        }
    }

    protected fun updateState(transform: (S) -> S) {
        mutableState.update(transform)
    }

    protected suspend fun emitEffect(effect: E) {
        mutableEffects.emit(effect)
    }

    /**
     * Applies lightweight state changes that must be visible before [dispatch] returns.
     * The returned intent may carry metadata needed by asynchronous handling.
     * Long-running work and side effects must remain in [handleIntent].
     */
    protected open fun reduceStateImmediately(intent: I): I = intent

    /** Called once after asynchronous handling completes or is cancelled before starting. */
    protected open fun onIntentHandlingCompleted(intent: I) = Unit

    protected abstract suspend fun handleIntent(intent: I)
}
