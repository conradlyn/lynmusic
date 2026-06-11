package top.iwesley.lyn.music.data.repository

internal data class PlaylistTextImportLine(
    val lineNumber: Int,
    val rawText: String,
    val title: String,
    val artist: String,
    val key: PlaylistTextImportKey,
)

internal data class PlaylistTextImportKey(
    val title: String,
    val artist: String,
)

private val PlaylistTextImportSpacedSeparator = Regex("""\s+-\s+""")

internal fun parsePlaylistTextImportLine(lineNumber: Int, rawText: String): PlaylistTextImportLine? {
    val spacedSeparator = PlaylistTextImportSpacedSeparator.findAll(rawText).lastOrNull()
    val title: String
    val artist: String
    if (spacedSeparator != null) {
        title = rawText.substring(0, spacedSeparator.range.first).trim()
        artist = rawText.substring(spacedSeparator.range.last + 1).trim()
    } else {
        val separatorIndex = rawText.lastIndexOf('-')
        if (separatorIndex < 0) return null
        title = rawText.substring(0, separatorIndex).trim()
        artist = rawText.substring(separatorIndex + 1).trim()
    }
    if (title.isBlank() || artist.isBlank()) return null
    return PlaylistTextImportLine(
        lineNumber = lineNumber,
        rawText = rawText,
        title = title,
        artist = artist,
        key = PlaylistTextImportKey(
            title = normalizePlaylistTextImportPart(title),
            artist = normalizePlaylistTextImportPart(artist),
        ),
    )
}

internal fun normalizePlaylistTextImportPart(value: String): String = value.trim().lowercase()
