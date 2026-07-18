package top.iwesley.lyn.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.iwesley.lyn.music.ui.LynMusicTheme

@Composable
internal fun JvmDesktopStartingScreen(
    modifier: Modifier = Modifier,
) {
    LynMusicTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Image(
                    painter = painterResource("desktop-icon.png"),
                    contentDescription = "LynMusic 应用图标",
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(28.dp)),
                )
                Text(
                    text = "LynMusic 正在启动…",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = StartupContentColor,
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = StartupContentColor,
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}

private val StartupContentColor = Color(0xFF202124)
