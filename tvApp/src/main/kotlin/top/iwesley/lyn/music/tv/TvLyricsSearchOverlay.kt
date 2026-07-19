package top.iwesley.lyn.music.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.iwesley.lyn.music.core.model.ArtworkCacheStore
import top.iwesley.lyn.music.core.model.LyricsSearchApplyMode
import top.iwesley.lyn.music.core.model.LyricsSearchCandidate
import top.iwesley.lyn.music.core.model.WorkflowSongCandidate
import top.iwesley.lyn.music.core.model.normalizedArtworkCacheLocator
import top.iwesley.lyn.music.core.model.resolveArtworkCacheTarget
import top.iwesley.lyn.music.domain.parseEnhancedLyricsPresentation
import top.iwesley.lyn.music.feature.player.PlayerIntent
import top.iwesley.lyn.music.feature.player.PlayerState

@Composable
internal fun TvLyricsSearchOverlay(
    state: PlayerState,
    artworkCacheStore: ArtworkCacheStore,
    onPlayerIntent: (PlayerIntent) -> Unit,
) {
    var confirmation by remember(state.snapshot.currentTrack?.id) {
        mutableStateOf<TvLyricsSearchConfirmation?>(null)
    }
    val searchFocusRequester = remember { FocusRequester() }
    val resultKeys = remember(state.manualLyricsResults, state.manualWorkflowSongResults) {
        buildList {
            state.manualLyricsResults.forEachIndexed { index, candidate ->
                add("direct:$index:${candidate.sourceId}:${candidate.itemId.orEmpty()}")
            }
            state.manualWorkflowSongResults.forEachIndexed { index, candidate ->
                add("workflow:$index:${candidate.sourceId}:${candidate.id}")
            }
        }
    }
    val resultFocusRequesters = remember(resultKeys) {
        List(resultKeys.size) { FocusRequester() }
    }
    var wasLoading by remember { mutableStateOf(state.isManualLyricsSearchLoading) }
    var confirmationReturnIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        searchFocusRequester.requestFocus()
    }
    LaunchedEffect(state.isManualLyricsSearchLoading, resultKeys) {
        if (wasLoading && !state.isManualLyricsSearchLoading && resultFocusRequesters.isNotEmpty()) {
            withFrameNanos { }
            resultFocusRequesters.first().requestFocus()
        }
        wasLoading = state.isManualLyricsSearchLoading
    }
    LaunchedEffect(confirmation) {
        if (confirmation == null) {
            val targetIndex = confirmationReturnIndex
            confirmationReturnIndex = null
            if (targetIndex != null) {
                withFrameNanos { }
                resultFocusRequesters.getOrNull(targetIndex)?.requestFocus()
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (confirmation != null) {
                confirmation = null
            } else {
                onPlayerIntent(PlayerIntent.DismissManualLyricsSearch)
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler {
            if (confirmation != null) {
                confirmation = null
            } else {
                onPlayerIntent(PlayerIntent.DismissManualLyricsSearch)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.74f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.90f),
                shape = TvLyricsSearchPanelShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    TvLyricsSearchForm(
                        state = state,
                        searchFocusRequester = searchFocusRequester,
                        onPlayerIntent = onPlayerIntent,
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight(),
                    )
                    TvLyricsSearchResults(
                        state = state,
                        artworkCacheStore = artworkCacheStore,
                        resultFocusRequesters = resultFocusRequesters,
                        onDirectCandidate = { index, candidate ->
                            confirmationReturnIndex = index
                            confirmation = TvLyricsSearchConfirmation.Direct(candidate)
                        },
                        onWorkflowCandidate = { index, candidate ->
                            confirmationReturnIndex = state.manualLyricsResults.size + index
                            confirmation = TvLyricsSearchConfirmation.Workflow(candidate)
                        },
                        modifier = Modifier
                            .weight(0.62f)
                            .fillMaxHeight(),
                    )
                }
            }
            confirmation?.let { pending ->
                TvLyricsSearchConfirmationOverlay(
                    confirmation = pending,
                    artworkCacheStore = artworkCacheStore,
                    onDismiss = { confirmation = null },
                    onApply = { mode ->
                        confirmation = null
                        when (pending) {
                            is TvLyricsSearchConfirmation.Direct -> onPlayerIntent(
                                PlayerIntent.ApplyManualLyricsCandidate(pending.candidate, mode),
                            )
                            is TvLyricsSearchConfirmation.Workflow -> onPlayerIntent(
                                PlayerIntent.ApplyWorkflowSongCandidate(pending.candidate, mode),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TvLyricsSearchForm(
    state: PlayerState,
    searchFocusRequester: FocusRequester,
    onPlayerIntent: (PlayerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .clip(TvLyricsSearchSectionShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                TvLyricsSearchSectionShape,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Text(
            text = "搜索条件",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "可修改当前歌曲信息，再向已启用歌词源搜索。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TvLyricsSearchTextField(
            value = state.manualLyricsTitle,
            onValueChange = { onPlayerIntent(PlayerIntent.ManualLyricsTitleChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "标题",
            imeAction = ImeAction.Next,
            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
        )
        TvLyricsSearchTextField(
            value = state.manualLyricsArtistName,
            onValueChange = { onPlayerIntent(PlayerIntent.ManualLyricsArtistChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "歌手",
            imeAction = ImeAction.Next,
            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
        )
        TvLyricsSearchTextField(
            value = state.manualLyricsAlbumTitle,
            onValueChange = { onPlayerIntent(PlayerIntent.ManualLyricsAlbumChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "专辑",
            imeAction = ImeAction.Search,
            onImeAction = {
                if (!state.isManualLyricsSearchLoading && state.manualLyricsTitle.isNotBlank()) {
                    onPlayerIntent(PlayerIntent.SearchManualLyrics)
                }
            },
        )
        Button(
            onClick = { onPlayerIntent(PlayerIntent.SearchManualLyrics) },
            enabled = !state.isManualLyricsSearchLoading && state.manualLyricsTitle.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocusRequester),
        ) {
            if (state.isManualLyricsSearchLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("搜索")
            }
        }
        state.manualLyricsError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.message
            ?.takeIf { it.isNotBlank() && it != state.manualLyricsError }
            ?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvLyricsSearchTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var editing by remember { mutableStateOf(false) }
    var imeWasVisibleDuringEditing by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }

    fun enterEditing() {
        imeWasVisibleDuringEditing = false
        fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
        editing = true
    }

    fun exitEditing() {
        imeWasVisibleDuringEditing = false
        editing = false
        keyboardController?.hide()
    }

    BackHandler(enabled = editing) {
        exitEditing()
    }

    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            keyboardController?.show()
        }
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    LaunchedEffect(editing, imeVisible) {
        if (!editing) {
            imeWasVisibleDuringEditing = false
            return@LaunchedEffect
        }
        if (imeVisible) {
            imeWasVisibleDuringEditing = true
        } else if (imeWasVisibleDuringEditing) {
            exitEditing()
        }
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { nextValue ->
            fieldValue = nextValue
            if (nextValue.text != value) {
                onValueChange(nextValue.text)
            }
        },
        label = { Text(label) },
        singleLine = true,
        readOnly = !editing,
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                exitEditing()
                onImeAction()
            },
            onSearch = {
                exitEditing()
                onImeAction()
            },
            onDone = {
                exitEditing()
                onImeAction()
            },
        ),
        modifier = modifier
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    exitEditing()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                    -> {
                        if (editing) {
                            exitEditing()
                        } else {
                            enterEditing()
                        }
                        true
                    }

                    Key.Back,
                    Key.Escape,
                    -> if (editing) {
                        exitEditing()
                        true
                    } else {
                        false
                    }

                    Key.DirectionUp -> {
                        exitEditing()
                        focusManager.moveFocus(FocusDirection.Up)
                    }

                    Key.DirectionDown -> {
                        exitEditing()
                        focusManager.moveFocus(FocusDirection.Down)
                    }

                    Key.DirectionLeft -> {
                        exitEditing()
                        focusManager.moveFocus(FocusDirection.Left)
                    }

                    Key.DirectionRight -> {
                        exitEditing()
                        focusManager.moveFocus(FocusDirection.Right)
                    }

                    else -> false
                }
            },
    )
}

@Composable
private fun TvLyricsSearchResults(
    state: PlayerState,
    artworkCacheStore: ArtworkCacheStore,
    resultFocusRequesters: List<FocusRequester>,
    onDirectCandidate: (Int, LyricsSearchCandidate) -> Unit,
    onWorkflowCandidate: (Int, WorkflowSongCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(TvLyricsSearchSectionShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                TvLyricsSearchSectionShape,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "搜索结果",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = tvLyricsSearchResultsSubtitle(state),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            state.isManualLyricsSearchLoading -> TvLyricsSearchStatus(
                message = "正在请求已启用的歌词源...",
                loading = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            state.manualLyricsResults.isNotEmpty() || state.manualWorkflowSongResults.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (state.manualLyricsResults.isNotEmpty()) {
                        item { TvLyricsSearchResultGroupTitle("直接歌词结果") }
                        itemsIndexed(state.manualLyricsResults) { index, candidate ->
                            TvLyricsSearchResultRow(
                                sourceName = candidate.sourceName,
                                title = candidate.title?.takeIf { it.isNotBlank() } ?: "歌词结果",
                                metadata = tvDirectLyricsMetadata(candidate),
                                preview = tvDirectLyricsPreview(candidate),
                                artworkLocator = candidate.artworkLocator,
                                artworkCacheStore = artworkCacheStore,
                                focusRequester = resultFocusRequesters.getOrNull(index),
                                onClick = { onDirectCandidate(index, candidate) },
                            )
                        }
                    }
                    if (state.manualWorkflowSongResults.isNotEmpty()) {
                        item { TvLyricsSearchResultGroupTitle("Workflow 歌曲候选") }
                        itemsIndexed(state.manualWorkflowSongResults) { index, candidate ->
                            val resultIndex = state.manualLyricsResults.size + index
                            TvLyricsSearchResultRow(
                                sourceName = candidate.sourceName,
                                title = candidate.title,
                                metadata = tvWorkflowLyricsMetadata(candidate),
                                preview = "选择后将继续请求该候选的歌词内容。",
                                artworkLocator = candidate.imageUrl,
                                artworkCacheStore = artworkCacheStore,
                                focusRequester = resultFocusRequesters.getOrNull(resultIndex),
                                onClick = { onWorkflowCandidate(index, candidate) },
                            )
                        }
                    }
                }
            }
            state.hasManualLyricsSearchResult -> TvLyricsSearchStatus(
                message = state.manualLyricsError ?: "没有找到可用歌词，可以调整搜索条件后重试。",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            else -> TvLyricsSearchStatus(
                message = "修改搜索条件后点击搜索，结果会显示在这里。",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun TvLyricsSearchResultGroupTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TvLyricsSearchResultRow(
    sourceName: String,
    title: String,
    metadata: String?,
    preview: String,
    artworkLocator: String?,
    artworkCacheStore: ArtworkCacheStore,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(shape)
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.055f),
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvLyricsSearchArtwork(
            artworkLocator = artworkLocator,
            artworkCacheStore = artworkCacheStore,
            modifier = Modifier.size(58.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceName,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            metadata?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvLyricsSearchStatus(
    message: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(34.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TvLyricsSearchArtwork(
    artworkLocator: String?,
    artworkCacheStore: ArtworkCacheStore,
    modifier: Modifier = Modifier,
) {
    val normalized = remember(artworkLocator) { normalizedArtworkCacheLocator(artworkLocator) }
    val model by produceState<String?>(
        initialValue = null,
        normalized,
        artworkCacheStore,
    ) {
        value = withContext(Dispatchers.IO) {
            normalized?.let { locator ->
                runCatching {
                    artworkCacheStore.cache(
                        locator = locator,
                        cacheKey = "$TvLyricsSearchArtworkPreviewCachePrefix$locator",
                    )
                }.getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: resolveArtworkCacheTarget(locator)
            }
        }
    }
    val painter = rememberAsyncImagePainter(model = model)
    val painterState by painter.state.collectAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null || painterState !is AsyncImagePainter.State.Success) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        if (model != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TvLyricsSearchConfirmationOverlay(
    confirmation: TvLyricsSearchConfirmation,
    artworkCacheStore: ArtworkCacheStore,
    onDismiss: () -> Unit,
    onApply: (LyricsSearchApplyMode) -> Unit,
) {
    val applyFocusRequester = remember { FocusRequester() }
    val lyricsFocusRequester = remember { FocusRequester() }
    val artworkFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val hasArtwork = normalizedArtworkCacheLocator(confirmation.artworkLocator) != null
    LaunchedEffect(confirmation) {
        withFrameNanos { }
        applyFocusRequester.requestFocus()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        text = "确认应用方式",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvLyricsSearchArtwork(
                            artworkLocator = confirmation.artworkLocator,
                            artworkCacheStore = artworkCacheStore,
                            modifier = Modifier.size(76.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = confirmation.sourceName,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = confirmation.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            confirmation.metadata?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onApply(LyricsSearchApplyMode.FULL) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(applyFocusRequester)
                                .focusProperties {
                                    left = applyFocusRequester
                                    right = lyricsFocusRequester
                                    up = applyFocusRequester
                                    down = if (hasArtwork) artworkFocusRequester else cancelFocusRequester
                                },
                        ) {
                            Text("应用")
                        }
                        OutlinedButton(
                            onClick = { onApply(LyricsSearchApplyMode.LYRICS_ONLY) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(lyricsFocusRequester)
                                .focusProperties {
                                    left = applyFocusRequester
                                    right = lyricsFocusRequester
                                    up = lyricsFocusRequester
                                    down = cancelFocusRequester
                                },
                        ) {
                            Text("仅应用歌词")
                        }
                    }
                    if (hasArtwork) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { onApply(LyricsSearchApplyMode.ARTWORK_ONLY) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(artworkFocusRequester)
                                    .focusProperties {
                                        left = artworkFocusRequester
                                        right = cancelFocusRequester
                                        up = applyFocusRequester
                                        down = artworkFocusRequester
                                    },
                            ) {
                                Text("仅应用封面")
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(cancelFocusRequester)
                                    .focusProperties {
                                        left = artworkFocusRequester
                                        right = cancelFocusRequester
                                        up = lyricsFocusRequester
                                        down = cancelFocusRequester
                                    },
                            ) {
                                Text("取消")
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(cancelFocusRequester)
                                .focusProperties {
                                    left = cancelFocusRequester
                                    right = cancelFocusRequester
                                    up = applyFocusRequester
                                    down = cancelFocusRequester
                                },
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
}

private fun tvLyricsSearchResultsSubtitle(state: PlayerState): String {
    return when {
        state.isManualLyricsSearchLoading -> "正在搜索已启用的歌词源。"
        state.manualLyricsResults.isNotEmpty() || state.manualWorkflowSongResults.isNotEmpty() ->
            "选择任一结果后设置应用方式。"
        state.hasManualLyricsSearchResult -> "当前没有可解析结果，可以继续调整搜索条件。"
        else -> "直接歌词结果和 Workflow 歌曲候选会显示在这里。"
    }
}

private fun tvDirectLyricsMetadata(candidate: LyricsSearchCandidate): String {
    return buildList {
        add(
            when {
                parseEnhancedLyricsPresentation(
                    rawPayload = candidate.document.rawPayload,
                    fallbackDocument = candidate.document,
                ) != null -> "逐字歌词"
                candidate.document.isSynced -> "逐行歌词"
                else -> "纯文本歌词"
            },
        )
        add("${candidate.document.lines.size} 行")
        candidate.artistName?.takeIf { it.isNotBlank() }?.let(::add)
        candidate.albumTitle?.takeIf { it.isNotBlank() }?.let(::add)
        candidate.durationSeconds?.takeIf { it > 0 }?.let { add(tvLyricsSearchDuration(it)) }
    }.joinToString(" · ")
}

private fun tvDirectLyricsPreview(candidate: LyricsSearchCandidate): String {
    return candidate.document.lines
        .asSequence()
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString(" / ")
        .ifBlank { "歌词内容为空" }
}

private fun tvWorkflowLyricsMetadata(candidate: WorkflowSongCandidate): String {
    return buildList {
        add(candidate.artists.joinToString(" / ").ifBlank { "未知歌手" })
        candidate.album?.takeIf { it.isNotBlank() }?.let(::add)
        candidate.durationSeconds?.takeIf { it > 0 }?.let { add(tvLyricsSearchDuration(it)) }
    }.joinToString(" · ")
}

private fun tvLyricsSearchDuration(durationSeconds: Int): String {
    val safeSeconds = durationSeconds.coerceAtLeast(0)
    return "${safeSeconds / 60}:${(safeSeconds % 60).toString().padStart(2, '0')}"
}

private sealed interface TvLyricsSearchConfirmation {
    val sourceName: String
    val title: String
    val metadata: String?
    val artworkLocator: String?

    data class Direct(val candidate: LyricsSearchCandidate) : TvLyricsSearchConfirmation {
        override val sourceName: String = candidate.sourceName
        override val title: String = candidate.title?.takeIf { it.isNotBlank() } ?: "歌词结果"
        override val metadata: String = tvDirectLyricsMetadata(candidate)
        override val artworkLocator: String? = candidate.artworkLocator
    }

    data class Workflow(val candidate: WorkflowSongCandidate) : TvLyricsSearchConfirmation {
        override val sourceName: String = candidate.sourceName
        override val title: String = candidate.title
        override val metadata: String = tvWorkflowLyricsMetadata(candidate)
        override val artworkLocator: String? = candidate.imageUrl
    }
}

private val TvLyricsSearchPanelShape = RoundedCornerShape(26.dp)
private val TvLyricsSearchSectionShape = RoundedCornerShape(20.dp)
private const val TvLyricsSearchArtworkPreviewCachePrefix = "lyrics-search-preview:"
