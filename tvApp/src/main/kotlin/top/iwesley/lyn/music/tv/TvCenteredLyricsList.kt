package top.iwesley.lyn.music.tv

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import top.iwesley.lyn.music.core.model.LyricsDocument

@Composable
internal fun TvCenteredLyricsList(
    lyrics: LyricsDocument?,
    highlightedLineIndex: Int,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    loadingMessage: String = "歌词加载中...",
    emptyMessage: String = "暂无歌词",
    messageContent: @Composable (String, Modifier) -> Unit,
) {
    val visibleLines = remember(lyrics) {
        lyrics?.lines
            ?.mapIndexedNotNull { index, line ->
                line.text.trim().takeIf { it.isNotBlank() }?.let { text ->
                    TvCenteredLyricsLine(rawIndex = index, text = text)
                }
            }
            .orEmpty()
    }
    val highlightedVisibleIndex = remember(visibleLines, highlightedLineIndex) {
        resolveTvCenteredLyricsHighlightedIndex(
            visibleLines = visibleLines,
            highlightedRawIndex = highlightedLineIndex,
        )
    }
    val scrollTargetIndex = remember(lyrics, visibleLines, highlightedVisibleIndex) {
        resolveTvCenteredLyricsScrollTarget(
            lyrics = lyrics,
            visibleLines = visibleLines,
            highlightedVisibleIndex = highlightedVisibleIndex,
        )
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lyrics, scrollTargetIndex, visibleLines.size) {
        val targetIndex = scrollTargetIndex ?: return@LaunchedEffect
        centerTvLyricsItem(
            listState = listState,
            targetIndex = targetIndex,
            itemCount = visibleLines.size,
        )
    }

    when {
        isLoading -> messageContent(loadingMessage, modifier)
        visibleLines.isEmpty() -> messageContent(emptyMessage, modifier)
        else -> BoxWithConstraints(modifier = modifier) {
            val centerPadding = (maxHeight / 2 - 36.dp).coerceAtLeast(56.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(listModifier),
                contentPadding = PaddingValues(vertical = centerPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(visibleLines, key = { _, line -> line.rawIndex }) { index, line ->
                    val highlighted = index == highlightedVisibleIndex
                    Text(
                        text = line.text,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.74f),
                        fontWeight = if (highlighted) FontWeight.ExtraBold else FontWeight.Medium,
                        style = if (highlighted) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private suspend fun centerTvLyricsItem(
    listState: LazyListState,
    targetIndex: Int,
    itemCount: Int,
) {
    if (targetIndex !in 0 until itemCount) return
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
        listState.scrollToItem(targetIndex)
        withFrameNanos { }
    }
    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        ?: return
    val viewportCenter =
        (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
    val itemCenter = itemInfo.offset + itemInfo.size / 2
    val delta = (itemCenter - viewportCenter).toFloat()
    if (abs(delta) > 1f) {
        listState.scrollBy(delta)
    }
}

private fun resolveTvCenteredLyricsScrollTarget(
    lyrics: LyricsDocument?,
    visibleLines: List<TvCenteredLyricsLine>,
    highlightedVisibleIndex: Int,
): Int? {
    if (lyrics == null || visibleLines.isEmpty()) return null
    return when (highlightedVisibleIndex) {
        in visibleLines.indices -> highlightedVisibleIndex
        else -> if (lyrics.isSynced) 0 else null
    }
}

private fun resolveTvCenteredLyricsHighlightedIndex(
    visibleLines: List<TvCenteredLyricsLine>,
    highlightedRawIndex: Int,
): Int {
    if (visibleLines.isEmpty() || highlightedRawIndex < 0) return -1
    visibleLines.indexOfFirst { it.rawIndex == highlightedRawIndex }
        .takeIf { it >= 0 }
        ?.let { return it }
    visibleLines.indexOfFirst { it.rawIndex > highlightedRawIndex }
        .takeIf { it >= 0 }
        ?.let { return it }
    return visibleLines.indexOfLast { it.rawIndex < highlightedRawIndex }
}

private data class TvCenteredLyricsLine(
    val rawIndex: Int,
    val text: String,
)
