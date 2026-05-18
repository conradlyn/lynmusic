package top.iwesley.lyn.music

import top.iwesley.lyn.music.core.model.Album
import top.iwesley.lyn.music.core.model.Artist
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.feature.library.LibrarySourceFilter
import top.iwesley.lyn.music.feature.library.libraryAlbumId
import top.iwesley.lyn.music.feature.library.libraryArtistId

internal sealed interface LibraryNavigationTarget {
    data class Album(
        val albumId: String,
        val preferredSourceFilter: LibrarySourceFilter = LibrarySourceFilter.ALL,
    ) : LibraryNavigationTarget

    data class Artist(
        val artistId: String,
        val preferredSourceFilter: LibrarySourceFilter = LibrarySourceFilter.ALL,
    ) : LibraryNavigationTarget
}

internal data class PlaybackLibraryNavigationTargets(
    val albumTarget: LibraryNavigationTarget.Album?,
    val artistTarget: LibraryNavigationTarget.Artist?,
)

internal data class LibraryNavigationResolution(
    val rootView: LibraryBrowserRootView,
    val selectedArtistId: String? = null,
    val selectedAlbumId: String? = null,
)

internal sealed interface LibraryNavigationCommand {
    data class ApplyContext(
        val sourceFilter: LibrarySourceFilter,
        val clearQuery: Boolean,
    ) : LibraryNavigationCommand

    data class Navigate(val resolution: LibraryNavigationResolution) : LibraryNavigationCommand
}

internal fun derivePlaybackLibraryNavigationTargets(
    snapshot: PlaybackSnapshot,
    track: Track,
): PlaybackLibraryNavigationTargets {
    val artistName = normalizedLibraryNavigationValue(snapshot.currentDisplayArtistName)
        ?: normalizedLibraryNavigationValue(track.artistName)
    val albumTitle = normalizedLibraryNavigationValue(snapshot.currentDisplayAlbumTitle)
        ?: normalizedLibraryNavigationValue(track.albumTitle)
    return deriveLibraryNavigationTargets(
        artistName = artistName,
        albumTitle = albumTitle,
    )
}

internal fun deriveTrackLibraryNavigationTargets(track: Track): PlaybackLibraryNavigationTargets {
    return deriveLibraryNavigationTargets(
        artistName = normalizedLibraryNavigationValue(track.artistName),
        albumTitle = normalizedLibraryNavigationValue(track.albumTitle),
    )
}

internal fun deriveTrackLibraryNavigationTargets(
    track: Track,
    preferredSourceFilter: LibrarySourceFilter,
): PlaybackLibraryNavigationTargets {
    return deriveLibraryNavigationTargets(
        artistName = normalizedLibraryNavigationValue(track.artistName),
        albumTitle = normalizedLibraryNavigationValue(track.albumTitle),
        preferredSourceFilter = preferredSourceFilter,
    )
}

private fun deriveLibraryNavigationTargets(
    artistName: String?,
    albumTitle: String?,
    preferredSourceFilter: LibrarySourceFilter = LibrarySourceFilter.ALL,
): PlaybackLibraryNavigationTargets {
    return PlaybackLibraryNavigationTargets(
        albumTarget = albumTitle?.let {
            LibraryNavigationTarget.Album(
                albumId = libraryAlbumId(artistName, it),
                preferredSourceFilter = preferredSourceFilter,
            )
        },
        artistTarget = artistName?.let {
            LibraryNavigationTarget.Artist(
                artistId = libraryArtistId(it),
                preferredSourceFilter = preferredSourceFilter,
            )
        },
    )
}

internal fun resolveLibraryNavigationCommand(
    target: LibraryNavigationTarget,
    query: String,
    selectedSourceFilter: LibrarySourceFilter,
    availableSourceFilters: List<LibrarySourceFilter>,
    filteredAlbums: List<Album>,
    filteredArtists: List<Artist>,
): LibraryNavigationCommand {
    val targetSourceFilter = target.navigationSourceFilter
        .takeIf { it == LibrarySourceFilter.ALL || it in availableSourceFilters }
        ?: LibrarySourceFilter.ALL
    if (query.isNotBlank() || selectedSourceFilter != targetSourceFilter) {
        return LibraryNavigationCommand.ApplyContext(
            sourceFilter = targetSourceFilter,
            clearQuery = query.isNotBlank(),
        )
    }
    return LibraryNavigationCommand.Navigate(
        when (target) {
            is LibraryNavigationTarget.Album -> {
                LibraryNavigationResolution(
                    rootView = LibraryBrowserRootView.Albums,
                    selectedAlbumId = target.albumId.takeIf { albumId ->
                        filteredAlbums.any { it.id == albumId }
                    },
                )
            }

            is LibraryNavigationTarget.Artist -> {
                LibraryNavigationResolution(
                    rootView = LibraryBrowserRootView.Artists,
                    selectedArtistId = target.artistId.takeIf { artistId ->
                        filteredArtists.any { it.id == artistId }
                    },
                )
            }
        },
    )
}

private val LibraryNavigationTarget.navigationSourceFilter: LibrarySourceFilter
    get() = when (this) {
        is LibraryNavigationTarget.Album -> preferredSourceFilter
        is LibraryNavigationTarget.Artist -> preferredSourceFilter
    }

private fun normalizedLibraryNavigationValue(value: String?): String? {
    return value?.trim()?.takeIf { it.isNotBlank() }
}
