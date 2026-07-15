package top.iwesley.lyn.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.iwesley.lyn.music.ui.LynMusicTheme
import top.iwesley.lyn.music.ui.mainShellColors

internal const val STARTUP_DATABASE_COMPATIBILITY_ERROR_TITLE =
    "数据库版本不兼容，请升级到最新版本或恢复备份"

internal const val STARTUP_DATABASE_COMPATIBILITY_ERROR_BODY =
    "当前版本无法打开本地数据库。为避免数据丢失，应用没有清空数据库。"

@Composable
@Suppress("DEPRECATION")
fun StartupDatabaseErrorScreen(
    error: Throwable?,
    showDetails: Boolean,
    modifier: Modifier = Modifier,
) {
    LynMusicTheme {
        val shellColors = mainShellColors
        val clipboardManager = LocalClipboardManager.current
        var detailsCopied by remember(error) { mutableStateOf(false) }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(shellColors.appGradientTop)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(shellColors.navContainer.copy(alpha = 0.94f))
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = STARTUP_DATABASE_COMPATIBILITY_ERROR_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = STARTUP_DATABASE_COMPATIBILITY_ERROR_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                startupDatabaseErrorDetails(error)
                    ?.takeIf { showDetails }
                    ?.let { details ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = details,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState())
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(shellColors.cardContainer.copy(alpha = 0.72f))
                                    .padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(details))
                                    detailsCopied = true
                                },
                            ) {
                                Text(if (detailsCopied) "已复制日志" else "复制错误日志")
                            }
                        }
                    }
            }
        }
    }
}

@Composable
fun StartupDataLocationProgressScreen(
    message: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    StartupDataLocationSurface(modifier) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.titleMedium)
        fraction?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "请勿关闭应用。数据准备完成后将自动进入 LynMusic。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StartupDataLocationErrorScreen(
    error: Throwable,
    canCancelChange: Boolean,
    onRetry: () -> Unit,
    onCancelChange: () -> Unit,
    onExitApplication: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StartupDataLocationSurface(modifier) {
        Text("数据位置切换失败", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            error.message ?: error.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (canCancelChange) {
                "当前位置尚未切换。你可以排查磁盘空间或权限后重试，也可以安全取消本次切换。"
            } else {
                "当前活动数据目录不可用。请恢复磁盘或目录后重试；应用不会自动改用其他目录。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("重试") }
            if (canCancelChange) {
                OutlinedButton(onClick = onCancelChange) { Text("取消切换") }
            } else {
                OutlinedButton(onClick = onExitApplication) { Text("退出应用") }
            }
        }
    }
}

@Composable
private fun StartupDataLocationSurface(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LynMusicTheme {
        val shellColors = mainShellColors
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(shellColors.appGradientTop)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(shellColors.navContainer.copy(alpha = 0.94f))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

internal fun startupDatabaseErrorDetails(error: Throwable?): String? {
    return error
        ?.stackTraceToString()
        ?.takeIf { it.isNotBlank() }
}
