package top.iwesley.lyn.music.tv

internal const val TV_PLAYER_ARTWORK_MAX_DECODE_SIZE_PX = 1280
internal const val TV_LYRICS_SEARCH_ARTWORK_MAX_DECODE_SIZE_PX = 512

internal data class TvPlayerArtworkRequestToken(
    val cacheKey: String?,
    val artworkLocator: String?,
    val cacheVersion: Long,
)

internal data class TvPlayerArtworkResolution(
    val requestToken: TvPlayerArtworkRequestToken,
    val target: String?,
)

internal fun TvPlayerArtworkResolution?.targetFor(
    currentRequestToken: TvPlayerArtworkRequestToken,
): String? = this?.takeIf { resolution -> resolution.requestToken == currentRequestToken }?.target

internal data class TvLyricsSearchArtworkRequestToken(
    val artworkLocator: String?,
)

internal data class TvLyricsSearchArtworkResolution(
    val requestToken: TvLyricsSearchArtworkRequestToken,
    val target: String?,
)

internal fun TvLyricsSearchArtworkResolution?.targetFor(
    currentRequestToken: TvLyricsSearchArtworkRequestToken,
): String? = this?.takeIf { resolution -> resolution.requestToken == currentRequestToken }?.target

internal fun TvPlayerArtworkRequestToken.memoryCacheIdentity(): String? {
    val cacheIdentity = cacheKey?.trim()?.takeIf { it.isNotEmpty() }
    val locatorIdentity = artworkLocator?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(cacheIdentity, locatorIdentity)
        .distinct()
        .takeIf { identities -> identities.isNotEmpty() }
        ?.joinToString(separator = "|") { identity -> "${identity.length}:$identity" }
}

internal fun tvPlayerArtworkMemoryCacheKey(
    artworkIdentity: String?,
    artworkCacheVersion: Long,
): String? {
    val identity = artworkIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "tv-player-artwork:$identity:$artworkCacheVersion:$TV_PLAYER_ARTWORK_MAX_DECODE_SIZE_PX"
}

internal fun tvLyricsSearchArtworkMemoryCacheKey(artworkIdentity: String?): String? {
    val identity = artworkIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "tv-lyrics-search-artwork:$identity:$TV_LYRICS_SEARCH_ARTWORK_MAX_DECODE_SIZE_PX"
}
