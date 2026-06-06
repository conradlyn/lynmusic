package top.iwesley.lyn.music

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.iwesley.lyn.music.core.model.PlaylistDetail
import top.iwesley.lyn.music.core.model.PlaylistSummary
import top.iwesley.lyn.music.data.repository.PlaylistImportLineIssue
import top.iwesley.lyn.music.data.repository.PlaylistImportReport

class PlaylistsUiLogicTest {
    @Test
    fun `detail loading stays hidden when no playlist is selected`() {
        val state = buildPlaylistDetailPresentationState(
            selectedPlaylistId = null,
            detail = null,
            playlists = samplePlaylists(),
        )

        assertFalse(state.shouldShowDetailPane)
        assertFalse(state.isDetailSwitchLoading)
        assertNull(state.resolvedDetail)
        assertNull(state.requestedPlaylistName)
    }

    @Test
    fun `detail loading shows while selected playlist detail is still missing`() {
        val state = buildPlaylistDetailPresentationState(
            selectedPlaylistId = "playlist-2",
            detail = null,
            playlists = samplePlaylists(),
        )

        assertTrue(state.shouldShowDetailPane)
        assertTrue(state.isDetailSwitchLoading)
        assertNull(state.resolvedDetail)
        assertEquals("通勤", state.requestedPlaylistName)
    }

    @Test
    fun `detail loading shows and hides stale detail when detail id does not match selection`() {
        val state = buildPlaylistDetailPresentationState(
            selectedPlaylistId = "playlist-2",
            detail = PlaylistDetail(id = "playlist-1", name = "晨跑"),
            playlists = samplePlaylists(),
        )

        assertTrue(state.shouldShowDetailPane)
        assertTrue(state.isDetailSwitchLoading)
        assertNull(state.resolvedDetail)
        assertEquals("通勤", state.requestedPlaylistName)
    }

    @Test
    fun `detail loading hides when matching playlist detail is ready`() {
        val detail = PlaylistDetail(id = "playlist-2", name = "通勤")
        val state = buildPlaylistDetailPresentationState(
            selectedPlaylistId = "playlist-2",
            detail = detail,
            playlists = samplePlaylists(),
        )

        assertTrue(state.shouldShowDetailPane)
        assertFalse(state.isDetailSwitchLoading)
        assertEquals(detail, state.resolvedDetail)
        assertEquals("通勤", state.requestedPlaylistName)
    }

    @Test
    fun `playlist summary artwork locator ignores blank values`() {
        assertNull(playlistSummaryArtworkLocator(PlaylistSummary(id = "empty", name = "空")))
        assertNull(playlistSummaryArtworkLocator(PlaylistSummary(id = "blank", name = "空白", artworkLocator = " ")))
        assertEquals(
            "/art/latest.jpg",
            playlistSummaryArtworkLocator(
                PlaylistSummary(id = "cover", name = "封面", artworkLocator = "/art/latest.jpg"),
            ),
        )
        assertNull(playlistSummaryArtworkCacheKey(PlaylistSummary(id = "empty-key", name = "空")))
        assertNull(
            playlistSummaryArtworkCacheKey(
                PlaylistSummary(id = "blank-key", name = "空白", artworkCacheKey = " "),
            ),
        )
        assertEquals(
            "album:local-1:album-1",
            playlistSummaryArtworkCacheKey(
                PlaylistSummary(id = "cover-key", name = "封面", artworkCacheKey = "album:local-1:album-1"),
            ),
        )
    }

    @Test
    fun `playlist import action is available only for loaded detail`() {
        assertFalse(canShowPlaylistImportAction(null))
        assertTrue(canShowPlaylistImportAction(PlaylistDetail(id = "playlist-1", name = "晨跑")))
    }

    @Test
    fun `playlist import confirm requires text and idle state`() {
        assertFalse(canConfirmPlaylistImport("", isImporting = false))
        assertFalse(canConfirmPlaylistImport("   ", isImporting = false))
        assertFalse(canConfirmPlaylistImport("咖啡恋曲 - 旺福", isImporting = true))
        assertTrue(canConfirmPlaylistImport("咖啡恋曲 - 旺福", isImporting = false))
    }

    @Test
    fun `playlist import assistant url stays fixed`() {
        assertEquals("https://music.unmeta.cn/", PlaylistImportAssistantUrl)
    }

    @Test
    fun `playlist import text field lines adapt to dialog height`() {
        assertEquals(2, playlistImportTextFieldLines(340.dp))
        assertEquals(3, playlistImportTextFieldLines(400.dp))
        assertEquals(4, playlistImportTextFieldLines(460.dp))
        assertEquals(6, playlistImportTextFieldLines(560.dp))
    }

    @Test
    fun `playlist import dialog layout shrinks for app display size`() {
        val defaultLayout = playlistImportDialogLayout(maxWidth = 393.dp, maxHeight = 820.dp)
        val largeDisplayLayout = playlistImportDialogLayout(maxWidth = 360.dp, maxHeight = 560.dp)

        assertEquals(560.dp, defaultLayout.maxHeight)
        assertTrue(largeDisplayLayout.maxHeight < defaultLayout.maxHeight)
        assertTrue(largeDisplayLayout.contentVerticalPadding < defaultLayout.contentVerticalPadding)
        assertEquals(3, largeDisplayLayout.textFieldLines)
    }

    @Test
    fun `playlist import dialog layout keeps usable minimum on tight height`() {
        val layout = playlistImportDialogLayout(maxWidth = 320.dp, maxHeight = 380.dp)

        assertEquals(12.dp, layout.outerHorizontalPadding)
        assertEquals(8.dp, layout.outerVerticalPadding)
        assertEquals(320.dp, layout.maxHeight)
        assertEquals(2, layout.textFieldLines)
    }

    @Test
    fun `playlist import report summary includes successful and skipped counts`() {
        val summary = playlistImportReportSummary(
            PlaylistImportReport(
                addedCount = 2,
                alreadyExistsCount = 1,
                duplicateInputCount = 1,
                malformedLines = listOf(PlaylistImportLineIssue(lineNumber = 4, rawText = "坏格式")),
                notMatchedLines = listOf(PlaylistImportLineIssue(lineNumber = 5, rawText = "找不到 - 歌手")),
            ),
        )

        assertEquals("已加入 2 首，已存在 1 首，重复 1 首，未导入 2 行", summary)
    }

    @Test
    fun `playlist track trailing width follows duration visibility`() {
        assertEquals(112.dp, playlistTrackTrailingWidth(selectionMode = false, showDuration = true))
        assertEquals(48.dp, playlistTrackTrailingWidth(selectionMode = false, showDuration = false))
        assertEquals(56.dp, playlistTrackTrailingWidth(selectionMode = true, showDuration = true))
        assertEquals(0.dp, playlistTrackTrailingWidth(selectionMode = true, showDuration = false))
    }

    private fun samplePlaylists(): List<PlaylistSummary> = listOf(
        PlaylistSummary(id = "playlist-1", name = "晨跑"),
        PlaylistSummary(id = "playlist-2", name = "通勤"),
    )
}
