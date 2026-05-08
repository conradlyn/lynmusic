package top.iwesley.lyn.music.platform

internal sealed interface CarBrowsableNode {
    data object Root : CarBrowsableNode
    data object All : CarBrowsableNode
    data object Favorites : CarBrowsableNode
    data object Playlists : CarBrowsableNode
    data class Playlist(val playlistId: String) : CarBrowsableNode
    data object Albums : CarBrowsableNode
    data class Album(val albumKey: String) : CarBrowsableNode
    data object Artists : CarBrowsableNode
    data class Artist(val artistName: String) : CarBrowsableNode
}

internal object CarMediaIds {
    const val ROOT = "root"
    const val ALL = "all"
    const val FAVORITES = "favorites"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val SCOPE_ALL = "all"
    const val SCOPE_FAVORITES = "favorites"
    const val SCOPE_SEARCH = "search"
    const val SCOPE_QUEUE_PREFIX = "queue:"
    const val SCOPE_PLAYLIST_PREFIX = "playlist:"
    const val SCOPE_ALBUM_PREFIX = "album:"
    const val SCOPE_ARTIST_PREFIX = "artist:"

    fun playlist(playlistId: String): String = "playlist|${playlistId.encodeMediaPart()}"
    fun album(albumKey: String): String = "album|${albumKey.encodeMediaPart()}"
    fun artist(artistName: String): String = "artist|${artistName.encodeMediaPart()}"
    fun queueScope(index: Int): String = "$SCOPE_QUEUE_PREFIX$index"
    fun playlistScope(playlistId: String): String = "$SCOPE_PLAYLIST_PREFIX$playlistId"
    fun albumScope(albumKey: String): String = "$SCOPE_ALBUM_PREFIX$albumKey"
    fun artistScope(artistName: String): String = "$SCOPE_ARTIST_PREFIX$artistName"
    fun track(scope: String, trackId: String): String {
        return "track|${scope.encodeMediaPart()}|${trackId.encodeMediaPart()}"
    }

    fun parseBrowsable(mediaId: String): CarBrowsableNode? {
        return when (mediaId) {
            ROOT -> CarBrowsableNode.Root
            ALL -> CarBrowsableNode.All
            FAVORITES -> CarBrowsableNode.Favorites
            PLAYLISTS -> CarBrowsableNode.Playlists
            ALBUMS -> CarBrowsableNode.Albums
            ARTISTS -> CarBrowsableNode.Artists
            else -> {
                val parts = mediaId.split('|')
                when (parts.firstOrNull()) {
                    "playlist" -> parts.getOrNull(1)?.decodeMediaPart()?.let(CarBrowsableNode::Playlist)
                    "album" -> parts.getOrNull(1)?.decodeMediaPart()?.let(CarBrowsableNode::Album)
                    "artist" -> parts.getOrNull(1)?.decodeMediaPart()?.let(CarBrowsableNode::Artist)
                    else -> null
                }
            }
        }
    }

    fun parsePlayable(mediaId: String): PlayableMediaId? {
        val parts = mediaId.split('|')
        if (parts.size != 3 || parts[0] != "track") return null
        return PlayableMediaId(
            scope = parts[1].decodeMediaPart(),
            trackId = parts[2].decodeMediaPart(),
        )
    }

    fun parseQueueIndex(scope: String): Int? {
        if (!scope.startsWith(SCOPE_QUEUE_PREFIX)) return null
        return scope.removePrefix(SCOPE_QUEUE_PREFIX)
            .toIntOrNull()
            ?.takeIf { it >= 0 }
    }
}

internal data class PlayableMediaId(
    val scope: String,
    val trackId: String,
)

private fun String.encodeMediaPart(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.decodeMediaPart(): String = java.net.URLDecoder.decode(this, Charsets.UTF_8.name())
