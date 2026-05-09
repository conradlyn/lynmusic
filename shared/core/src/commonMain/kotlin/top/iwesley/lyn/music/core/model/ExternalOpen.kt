package top.iwesley.lyn.music.core.model

const val EXTERNAL_OPEN_SOURCE_ID: String = "external-open"

private const val EXTERNAL_OPEN_TRACK_ID_PREFIX = "external-open:"

fun isExternalOpenTrack(track: Track): Boolean {
    return track.sourceId == EXTERNAL_OPEN_SOURCE_ID || track.id.startsWith(EXTERNAL_OPEN_TRACK_ID_PREFIX)
}

fun buildExternalOpenTrackId(mediaLocator: String, index: Int): String {
    val normalizedIndex = index.coerceAtLeast(0)
    return "$EXTERNAL_OPEN_TRACK_ID_PREFIX${stableExternalOpenHash(mediaLocator)}:$normalizedIndex"
}

private fun stableExternalOpenHash(value: String): String {
    var hash = 1125899906842597L
    value.forEach { char ->
        hash = 31L * hash + char.code
    }
    return hash.toString().replace("-", "n")
}
