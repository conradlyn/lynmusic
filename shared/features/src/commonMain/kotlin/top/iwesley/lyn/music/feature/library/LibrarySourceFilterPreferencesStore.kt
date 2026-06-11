package top.iwesley.lyn.music.feature.library

import kotlinx.coroutines.flow.StateFlow

interface LibrarySourceFilterPreferencesStore {
    val librarySourceFilter: StateFlow<LibrarySourceFilter>
    val favoritesSourceFilter: StateFlow<LibrarySourceFilter>
    val libraryTrackSortMode: StateFlow<TrackSortMode>
    val favoritesTrackSortMode: StateFlow<TrackSortMode>
    val onlineLibrarySourceId: StateFlow<String?>
    val onlineFavoritesSourceId: StateFlow<String?>
    val onlinePlaylistsSourceId: StateFlow<String?>

    suspend fun setLibrarySourceFilter(filter: LibrarySourceFilter)

    suspend fun setFavoritesSourceFilter(filter: LibrarySourceFilter)

    suspend fun setLibraryTrackSortMode(mode: TrackSortMode)

    suspend fun setFavoritesTrackSortMode(mode: TrackSortMode)

    suspend fun setOnlineLibrarySourceId(sourceId: String?)

    suspend fun setOnlineFavoritesSourceId(sourceId: String?)

    suspend fun setOnlinePlaylistsSourceId(sourceId: String?)
}
