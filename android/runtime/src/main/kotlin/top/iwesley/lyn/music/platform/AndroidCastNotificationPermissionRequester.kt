package top.iwesley.lyn.music.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.iwesley.lyn.music.cast.CastNotificationPermissionRequester
import top.iwesley.lyn.music.core.model.GlobalDiagnosticLogger

class AndroidCastNotificationPermissionRequester(
    context: Context,
    private val activityActions: AndroidActivityActions,
) : CastNotificationPermissionRequester {
    constructor(activity: ComponentActivity) : this(
        context = activity.applicationContext,
        activityActions = FixedAndroidActivityActions(activity, GlobalDiagnosticLogger),
    )

    private val context: Context = context.applicationContext
    private val mutex = Mutex()

    override fun isRequestNeeded(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()
    }

    override suspend fun requestIfNeeded(): Boolean {
        if (!isRequestNeeded()) return true
        return mutex.withLock {
            if (!isRequestNeeded()) {
                return@withLock true
            }
            activityActions.requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }
}
