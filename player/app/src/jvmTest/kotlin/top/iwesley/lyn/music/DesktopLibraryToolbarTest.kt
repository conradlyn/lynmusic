package top.iwesley.lyn.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLibraryToolbarTest {
    @Test
    fun `desktop library search field width is fixed`() {
        assertEquals(200, desktopLibrarySearchFieldWidthDp())
    }

    @Test
    fun `desktop library search field height and corner radius are compact`() {
        assertEquals(40, desktopLibrarySearchFieldHeightDp())
        assertEquals(8, desktopLibrarySearchFieldCornerRadiusDp())
    }

    @Test
    fun `desktop library search clear button only shows for non blank query`() {
        assertFalse(shouldShowDesktopLibrarySearchClearButton(""))
        assertFalse(shouldShowDesktopLibrarySearchClearButton("   "))
        assertTrue(shouldShowDesktopLibrarySearchClearButton("jay"))
    }

    @Test
    fun `desktop library toolbar only applies to desktop search layout`() {
        assertTrue(useDesktopLibraryBrowserToolbar(showSearchField = true, showDuration = true))
        assertFalse(useDesktopLibraryBrowserToolbar(showSearchField = true, showDuration = false))
        assertFalse(useDesktopLibraryBrowserToolbar(showSearchField = false, showDuration = true))
    }

    @Test
    fun `desktop library toolbar shows source sort and optional action buttons`() {
        assertEquals(
            DesktopLibraryToolbarActions(
                showsSourceFilter = true,
                showsTrackSort = true,
                showsActionButton = true,
            ),
            resolveDesktopLibraryToolbarActions(
                showSearchField = true,
                showDuration = true,
                showTrackSortMenu = true,
                hasActionButton = true,
            ),
        )
        assertEquals(
            DesktopLibraryToolbarActions(
                showsSourceFilter = true,
                showsTrackSort = false,
                showsActionButton = false,
            ),
            resolveDesktopLibraryToolbarActions(
                showSearchField = true,
                showDuration = true,
                showTrackSortMenu = false,
                hasActionButton = false,
            ),
        )
    }

    @Test
    fun `mobile compact hidden search does not use desktop toolbar actions`() {
        assertEquals(
            DesktopLibraryToolbarActions(
                showsSourceFilter = false,
                showsTrackSort = false,
                showsActionButton = false,
            ),
            resolveDesktopLibraryToolbarActions(
                showSearchField = false,
                showDuration = false,
                showTrackSortMenu = true,
                hasActionButton = true,
            ),
        )
    }

    @Test
    fun `default library root selector keeps four peer entries`() {
        val model = buildLibraryRootSelectorModel(
            style = LibraryRootSelectorStyle.Default,
            trackCount = 12,
            albumCount = 3,
            artistCount = 2,
            folderCount = 5,
            showFolderBrowser = true,
        )

        assertEquals(LibraryRootSelectorStyle.Default, model.style)
        assertEquals(
            listOf(
                LibraryBrowserRootView.Tracks,
                LibraryBrowserRootView.Albums,
                LibraryBrowserRootView.Artists,
                LibraryBrowserRootView.Folders,
            ),
            model.defaultItems.map { it.rootView },
        )
        assertEquals(null, model.heroItem)
        assertEquals(emptyList(), model.secondaryItems)
        assertFalse(model.playAllEnabled)
    }

    @Test
    fun `compact library root selector uses song hero and three secondary entries`() {
        val model = buildLibraryRootSelectorModel(
            style = LibraryRootSelectorStyle.CompactHero,
            trackCount = 5458,
            albumCount = 744,
            artistCount = 293,
            folderCount = 704,
            showFolderBrowser = true,
        )

        assertEquals(LibraryRootSelectorStyle.CompactHero, model.style)
        assertEquals(
            LibraryRootSelectorItem(
                rootView = LibraryBrowserRootView.Tracks,
                title = "全部歌曲",
                value = "5458",
            ),
            model.heroItem,
        )
        assertEquals(
            listOf(
                LibraryBrowserRootView.Albums,
                LibraryBrowserRootView.Artists,
                LibraryBrowserRootView.Folders,
            ),
            model.secondaryItems.map { it.rootView },
        )
        assertTrue(model.playAllEnabled)
    }

    @Test
    fun `compact library root selector disables play all without tracks`() {
        val model = buildLibraryRootSelectorModel(
            style = LibraryRootSelectorStyle.CompactHero,
            trackCount = 0,
            albumCount = 0,
            artistCount = 0,
            folderCount = 0,
            showFolderBrowser = true,
        )

        assertFalse(model.playAllEnabled)
        assertEquals("0", model.heroItem?.value)
    }
}
