package top.iwesley.lyn.music.feature.library

import top.iwesley.lyn.music.core.model.Album
import top.iwesley.lyn.music.core.model.Artist
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.data.repository.OnlineAlbumItem
import top.iwesley.lyn.music.data.repository.OnlineArtistItem
import top.iwesley.lyn.music.feature.online.OnlineFavoritesState
import top.iwesley.lyn.music.feature.online.OnlineLibraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryBrowserUiModelsTest {
    @Test
    fun `library browser count displays exact total when available`() {
        assertEquals(
            "450000",
            LibraryBrowserCount(loaded = 100, total = 450000, hasMore = true).displayValue(),
        )
    }

    @Test
    fun `library browser count displays loaded plus when total is unknown and has more`() {
        assertEquals(
            "100+",
            LibraryBrowserCount(loaded = 100, total = null, hasMore = true).displayValue(),
        )
    }

    @Test
    fun `library browser count displays loaded count when total is unknown and no more pages`() {
        assertEquals(
            "87",
            LibraryBrowserCount(loaded = 87, total = null, hasMore = false).displayValue(),
        )
    }

    @Test
    fun `online library adapter exposes remote totals and unknown paged counts`() {
        val uiState = OnlineLibraryState(
            sourceId = "navidrome",
            tracks = tracks(count = 100),
            albums = onlineAlbums(count = 100),
            artists = onlineArtists(count = 87),
            totalTrackCount = 450000,
            totalAlbumCount = null,
            totalArtistCount = 87,
            canLoadMoreTracks = true,
            canLoadMoreAlbums = true,
            canLoadMoreArtists = false,
        ).toBrowserUiState()

        assertEquals("450000", uiState.trackCount.displayValue())
        assertEquals("100+", uiState.albumCount.displayValue())
        assertEquals("87", uiState.artistCount.displayValue())
    }

    @Test
    fun `online library adapter exposes album artwork locator`() {
        val uiState = OnlineLibraryState(
            sourceId = "navidrome",
            albums = listOf(
                OnlineAlbumItem(
                    album = albums(count = 1).single(),
                    artworkLocator = "lynmusic-navidrome-cover://source/cover-1",
                ),
            ),
        ).toBrowserUiState()

        assertEquals("lynmusic-navidrome-cover://source/cover-1", uiState.albums.single().artworkLocator)
    }

    @Test
    fun `online library adapter exposes artist nullable counts and detail albums`() {
        val uiState = OnlineLibraryState(
            sourceId = "navidrome",
            artists = listOf(
                OnlineArtistItem(
                    artist = Artist(id = "artist-unknown", name = "Unknown Artist"),
                ),
                OnlineArtistItem(
                    artist = Artist(id = "artist-counted", name = "Counted Artist"),
                    trackCount = 31,
                    albumCount = 7,
                ),
            ),
            selectedArtistAlbumsById = mapOf(
                "artist-counted" to listOf(
                    OnlineAlbumItem(
                        album = Album(id = "album-counted", title = "Counted Album", artistName = "Counted Artist"),
                        artworkLocator = "lynmusic-navidrome-cover://source/artist-album-cover",
                    ),
                ),
            ),
            loadingArtistAlbumIds = setOf("artist-unknown"),
        ).toBrowserUiState()

        assertNull(uiState.artists[0].trackCount)
        assertNull(uiState.artists[0].albumCount)
        assertEquals(31, uiState.artists[1].trackCount)
        assertEquals(7, uiState.artists[1].albumCount)
        assertEquals(setOf("artist-unknown"), uiState.loadingArtistAlbumIds)
        assertEquals(
            "lynmusic-navidrome-cover://source/artist-album-cover",
            uiState.onlineArtistAlbumsById.getValue("artist-counted").single().artworkLocator,
        )
    }

    @Test
    fun `online favorites adapter derives album and artist roots from visible tracks`() {
        val alphaFirst = track(
            id = "alpha-1",
            title = "Alpha One",
            artistName = "Artist A",
            albumTitle = "Album A",
            artworkLocator = "lynmusic-navidrome-cover://source/album-a",
        )
        val alphaSecond = track(
            id = "alpha-2",
            title = "Alpha Two",
            artistName = "Artist A",
            albumTitle = "Album A",
        )
        val beta = track(
            id = "beta-1",
            title = "Beta One",
            artistName = "Artist B",
            albumTitle = "Album B",
        )

        val uiState = OnlineFavoritesState(
            sourceId = "navidrome",
            tracks = listOf(alphaFirst, alphaSecond, beta),
        ).toBrowserUiState()

        val albumAId = libraryAlbumId("Artist A", "Album A")
        val artistAId = libraryArtistId("Artist A")

        assertEquals("3", uiState.trackCount.displayValue())
        assertEquals("2", uiState.albumCount.displayValue())
        assertEquals("2", uiState.artistCount.displayValue())
        assertEquals(listOf("Album A", "Album B"), uiState.albums.map { it.album.title })
        assertEquals(
            "lynmusic-navidrome-cover://source/album-a",
            uiState.onlineAlbumItemsById.getValue(albumAId).artworkLocator,
        )
        assertEquals(listOf("alpha-1", "alpha-2"), uiState.onlineAlbumTracksById.getValue(albumAId).map { it.id })
        assertEquals(2, uiState.onlineArtistItemsById.getValue(artistAId).trackCount)
        assertEquals(1, uiState.onlineArtistItemsById.getValue(artistAId).albumCount)
        assertEquals(listOf(albumAId), uiState.onlineArtistAlbumsById.getValue(artistAId).map { it.id })
    }

    @Test
    fun `online favorites adapter marks derived album and artist counts as partial while more tracks can load`() {
        val uiState = OnlineFavoritesState(
            sourceId = "navidrome",
            tracks = listOf(
                track(
                    id = "alpha-1",
                    title = "Alpha One",
                    artistName = "Artist A",
                    albumTitle = "Album A",
                ),
            ),
            canLoadMore = true,
        ).toBrowserUiState()

        assertEquals("1+", uiState.albumCount.displayValue())
        assertEquals("1+", uiState.artistCount.displayValue())
    }

    @Test
    fun `local library adapter exposes exact visible counts`() {
        val uiState = LibraryState(
            tracks = tracks(count = 3),
            filteredTracks = tracks(count = 2),
            filteredAlbums = albums(count = 1),
            filteredArtists = artists(count = 1),
        ).toBrowserUiState()

        assertEquals("2", uiState.trackCount.displayValue())
        assertEquals("1", uiState.albumCount.displayValue())
        assertEquals("1", uiState.artistCount.displayValue())
        assertNull(uiState.albums.single().artworkLocator)
    }

    private fun track(
        id: String,
        title: String,
        artistName: String?,
        albumTitle: String?,
        artworkLocator: String? = null,
    ): Track {
        return Track(
            id = id,
            sourceId = "source",
            title = title,
            artistName = artistName,
            albumTitle = albumTitle,
            artworkLocator = artworkLocator,
            mediaLocator = "file:///$id.mp3",
            relativePath = "$id.mp3",
        )
    }

    private fun tracks(count: Int): List<Track> {
        return List(count) { index ->
            Track(
                id = "track-$index",
                sourceId = "source",
                title = "Track $index",
                artistName = "Artist $index",
                albumTitle = "Album $index",
                mediaLocator = "file:///track-$index.mp3",
                relativePath = "track-$index.mp3",
            )
        }
    }

    private fun albums(count: Int): List<Album> {
        return List(count) { index ->
            Album(
                id = "album-$index",
                title = "Album $index",
                artistName = "Artist $index",
            )
        }
    }

    private fun onlineAlbums(count: Int): List<OnlineAlbumItem> {
        return albums(count).map { album -> OnlineAlbumItem(album = album) }
    }

    private fun onlineArtists(count: Int): List<OnlineArtistItem> {
        return artists(count).map { artist -> OnlineArtistItem(artist = artist) }
    }

    private fun artists(count: Int): List<Artist> {
        return List(count) { index ->
            Artist(
                id = "artist-$index",
                name = "Artist $index",
            )
        }
    }
}
