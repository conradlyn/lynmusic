package top.iwesley.lyn.music

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import top.iwesley.lyn.music.platform.createJvmAppComponent

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    installJvmUncaughtExceptionHandler()
    application {
        val appComponentResult = remember { runCatching { createJvmAppComponent() } }
        val appComponent = appComponentResult.getOrNull()
        val desktopWindowChrome = remember {
            defaultDesktopWindowChrome(System.getProperty("os.name").orEmpty())
        }
        val windowState = rememberWindowState(
            size = DpSize(1440.dp, 900.dp),
        )
        SwingWindow(
            onCloseRequest = {
                val settingsStore = appComponent?.settingsStore
                val supportsMacOsWindowCloseBehavior =
                    appComponent?.platform?.capabilities?.supportsMacOsWindowCloseBehavior == true
                val persistenceCompleted = if (supportsMacOsWindowCloseBehavior && settingsStore != null) {
                    runBlocking {
                        withTimeoutOrNull(WINDOW_CLOSE_PREFERENCE_FLUSH_TIMEOUT_MILLIS) {
                            settingsStore.awaitMinimizeWindowOnClosePersistence()
                            true
                        } ?: false
                    }
                } else {
                    true
                }
                val minimizeWindowOnClose = resolveMinimizeWindowOnClosePreference(
                    persistenceCompleted = persistenceCompleted,
                    currentValue = settingsStore?.state?.value?.minimizeWindowOnClose == true,
                    persistedValue = settingsStore?.persistedMinimizeWindowOnClose == true,
                )
                val shouldMinimize = shouldMinimizeDesktopWindowOnClose(
                    supportsMacOsWindowCloseBehavior = supportsMacOsWindowCloseBehavior,
                    minimizeWindowOnClose = minimizeWindowOnClose,
                )
                if (shouldMinimize) {
                    windowState.isMinimized = true
                } else {
                    exitApplication()
                }
            },
            title = "LynMusic",
            state = windowState,
            icon = painterResource("desktop-icon.png"),
            init = { composeWindow ->
                composeWindow.minimumSize = Dimension(1200, 720)
                applyDesktopWindowChrome(composeWindow, desktopWindowChrome)
            },
        ) {
            if (appComponent != null) {
                App(
                    component = appComponent,
                    desktopWindowChrome = desktopWindowChrome,
                )
            } else {
                StartupDatabaseErrorScreen(
                    error = appComponentResult.exceptionOrNull(),
                    showDetails = true,
                )
            }
        }
    }
}

private const val WINDOW_CLOSE_PREFERENCE_FLUSH_TIMEOUT_MILLIS = 2_000L

internal fun resolveMinimizeWindowOnClosePreference(
    persistenceCompleted: Boolean,
    currentValue: Boolean,
    persistedValue: Boolean,
): Boolean {
    return if (persistenceCompleted) currentValue else persistedValue
}

internal fun shouldMinimizeDesktopWindowOnClose(
    supportsMacOsWindowCloseBehavior: Boolean,
    minimizeWindowOnClose: Boolean,
): Boolean {
    return supportsMacOsWindowCloseBehavior && minimizeWindowOnClose
}

internal fun defaultDesktopWindowChrome(osName: String): DesktopWindowChrome {
    return if (isJvmMacOs(osName)) {
        DesktopWindowChrome(
            immersiveTitleBarEnabled = true,
            topInset = 40.dp,
            dragRegionHeight = 40.dp,
        )
    } else {
        DesktopWindowChrome()
    }
}

internal fun isJvmMacOs(osName: String): Boolean {
    return osName.contains("mac", ignoreCase = true)
}

internal fun macOsImmersiveAwtClientProperties(): Map<String, Any> {
    return linkedMapOf(
        "apple.awt.fullWindowContent" to true,
        "apple.awt.transparentTitleBar" to true,
        "apple.awt.windowTitleVisible" to false,
    )
}

internal fun applyDesktopWindowChrome(
    window: java.awt.Window,
    desktopWindowChrome: DesktopWindowChrome,
) {
    if (!desktopWindowChrome.immersiveTitleBarEnabled || window !is javax.swing.RootPaneContainer) return
    macOsImmersiveAwtClientProperties().forEach { (key, value) ->
        window.rootPane.putClientProperty(key, value)
    }
}
