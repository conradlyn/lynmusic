package top.iwesley.lyn.music.platform

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.GlobalDiagnosticLogger
import top.iwesley.lyn.music.core.model.LocalFolderPickerMode
import top.iwesley.lyn.music.core.model.LocalFolderSelection
import top.iwesley.lyn.music.core.model.debug
import kotlin.coroutines.resume

/**
 * Application-scoped bridge for operations which must be launched by the currently visible Activity.
 * Implementations must not retain an Activity after it has stopped.
 */
interface AndroidActivityActions {
    val activityResumedEvents: Flow<Unit>

    suspend fun pickLocalFolder(mode: LocalFolderPickerMode): LocalFolderSelection?

    suspend fun pickContent(mimeType: String): Uri?

    suspend fun requestPermission(permission: String): Boolean
}

class MutableAndroidActivityActions(
    private val logger: DiagnosticLogger = GlobalDiagnosticLogger,
) : AndroidActivityActions {
    private val resumedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val activeHosts = WeakActiveHostRegistry<AndroidActivityActionHost>()

    override val activityResumedEvents: Flow<Unit> = resumedEvents.asSharedFlow()

    internal fun bind(host: AndroidActivityActionHost) {
        activeHosts.bind(host)
            ?.takeIf { previous -> previous !== host }
            ?.cancelPendingRequests()
        logger.debug(ACTIVITY_ACTIONS_LOG_TAG) { "activity-host-bound activity=${host.activityName}" }
    }

    internal fun unbind(host: AndroidActivityActionHost) {
        if (activeHosts.unbind(host)) {
            logger.debug(ACTIVITY_ACTIONS_LOG_TAG) { "activity-host-unbound activity=${host.activityName}" }
        }
    }

    internal fun notifyResumed(host: AndroidActivityActionHost) {
        if (activeHost() === host) {
            resumedEvents.tryEmit(Unit)
        }
    }

    override suspend fun pickLocalFolder(mode: LocalFolderPickerMode): LocalFolderSelection? {
        return activeHost()?.pickLocalFolder(mode)
    }

    override suspend fun pickContent(mimeType: String): Uri? {
        return activeHost()?.pickContent(mimeType)
    }

    override suspend fun requestPermission(permission: String): Boolean {
        return activeHost()?.requestPermission(permission) ?: false
    }

    private fun activeHost(): AndroidActivityActionHost? = activeHosts.current()
}

internal class WeakActiveHostRegistry<T : Any> {
    private var activeReference: WeakReference<T>? = null

    @Synchronized
    fun bind(host: T): T? {
        val previous = activeReference?.get()
        activeReference = WeakReference(host)
        return previous
    }

    @Synchronized
    fun unbind(host: T): Boolean {
        if (activeReference?.get() !== host) return false
        activeReference = null
        return true
    }

    @Synchronized
    fun current(): T? {
        val host = activeReference?.get()
        if (host == null) activeReference = null
        return host
    }
}

internal class SerializedActivityRequestGate {
    private val mutex = Mutex()
    private val generation = AtomicLong(0L)

    @Volatile
    private var active = true

    suspend fun <T> runOrNull(block: suspend () -> T?): T? {
        val requestGeneration = generation.get()
        if (!active || requestGeneration != generation.get()) return null
        return mutex.withLock {
            if (!active || requestGeneration != generation.get()) return@withLock null
            block()
        }
    }

    fun activate() {
        active = true
    }

    fun invalidatePendingRequests() {
        active = false
        generation.incrementAndGet()
    }
}

class AndroidActivityActionHost(
    private val activity: ComponentActivity,
    private val actions: MutableAndroidActivityActions,
    logger: DiagnosticLogger = GlobalDiagnosticLogger,
) {
    internal val activityName: String = activity::class.java.simpleName
    private val localFolderPicker = AndroidLocalFolderPicker(activity, logger)
    private val folderRequestGate = SerializedActivityRequestGate()
    private val contentRequestGate = SerializedActivityRequestGate()
    private val permissionRequestGate = SerializedActivityRequestGate()
    private var contentContinuation: ((Uri?) -> Unit)? = null
    private var permissionContinuation: ((Boolean) -> Unit)? = null

    private val contentPicker = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val continuation = contentContinuation
        contentContinuation = null
        continuation?.invoke(uri)
    }
    private val permissionRequester = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val continuation = permissionContinuation
        permissionContinuation = null
        continuation?.invoke(granted)
    }

    fun bind() {
        folderRequestGate.activate()
        contentRequestGate.activate()
        permissionRequestGate.activate()
        actions.bind(this)
    }

    fun notifyResumed() {
        actions.notifyResumed(this)
    }

    fun unbind() {
        invalidateUnstartedRequests()
        actions.unbind(this)
    }

    fun close() {
        actions.unbind(this)
        cancelPendingRequests()
    }

    internal suspend fun pickLocalFolder(mode: LocalFolderPickerMode): LocalFolderSelection? =
        withContext(Dispatchers.Main.immediate) {
            folderRequestGate.runOrNull {
                localFolderPicker.pickLocalFolder(mode)
            }
        }

    internal suspend fun pickContent(mimeType: String): Uri? = withContext(Dispatchers.Main.immediate) {
        contentRequestGate.runOrNull {
            suspendCancellableCoroutine { continuation ->
                contentContinuation = { uri ->
                    if (continuation.isActive) continuation.resume(uri)
                }
                continuation.invokeOnCancellation { contentContinuation = null }
                runCatching { contentPicker.launch(mimeType) }
                    .onFailure {
                        contentContinuation = null
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        }
    }

    internal suspend fun requestPermission(permission: String): Boolean = withContext(Dispatchers.Main.immediate) {
        permissionRequestGate.runOrNull {
            if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
                return@runOrNull true
            }
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = { granted ->
                    if (continuation.isActive) continuation.resume(granted)
                }
                continuation.invokeOnCancellation { permissionContinuation = null }
                runCatching { permissionRequester.launch(permission) }
                    .onFailure {
                        permissionContinuation = null
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        } ?: false
    }

    internal fun cancelPendingRequests() {
        invalidateUnstartedRequests()
        localFolderPicker.cancelPendingRequest()
        contentContinuation?.invoke(null)
        contentContinuation = null
        permissionContinuation?.invoke(false)
        permissionContinuation = null
    }

    private fun invalidateUnstartedRequests() {
        folderRequestGate.invalidatePendingRequests()
        contentRequestGate.invalidatePendingRequests()
        permissionRequestGate.invalidatePendingRequests()
    }
}

internal class FixedAndroidActivityActions(
    activity: ComponentActivity,
    logger: DiagnosticLogger,
) : AndroidActivityActions {
    private val gateway = MutableAndroidActivityActions(logger)
    private val host = AndroidActivityActionHost(activity, gateway, logger)
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            host.bind()
        }

        override fun onResume(owner: LifecycleOwner) {
            host.notifyResumed()
        }

        override fun onStop(owner: LifecycleOwner) {
            host.unbind()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            host.close()
            owner.lifecycle.removeObserver(this)
        }
    }

    init {
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    override val activityResumedEvents: Flow<Unit> = gateway.activityResumedEvents

    override suspend fun pickLocalFolder(mode: LocalFolderPickerMode): LocalFolderSelection? =
        gateway.pickLocalFolder(mode)

    override suspend fun pickContent(mimeType: String): Uri? = gateway.pickContent(mimeType)

    override suspend fun requestPermission(permission: String): Boolean = gateway.requestPermission(permission)
}

private const val ACTIVITY_ACTIONS_LOG_TAG = "AndroidActivityActions"
