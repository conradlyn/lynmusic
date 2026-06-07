package top.iwesley.lyn.music.core.model

object LynMusicUpdateLinks {
    const val PROJECT_URL = "https://github.com/wesley666/LynMusic"
    const val RELEASES_URL = "$PROJECT_URL/releases"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/wesley666/LynMusic/releases/latest"
}

fun isAppReleaseNewer(
    currentVersionName: String,
    releaseTagName: String,
): Boolean {
    return compareAppVersionNames(releaseTagName, currentVersionName) > 0
}

fun compareAppVersionNames(left: String, right: String): Int {
    return compareParsedAppVersions(left.parseAppVersion(), right.parseAppVersion())
}

private data class ParsedAppVersion(
    val segments: List<Long>,
    val prerelease: String?,
)

private fun String.parseAppVersion(): ParsedAppVersion {
    val trimmed = trim()
    val normalized = if (
        trimmed.length > 1 &&
        (trimmed[0] == 'v' || trimmed[0] == 'V') &&
        trimmed[1].isDigit()
    ) {
        trimmed.drop(1)
    } else {
        trimmed
    }
    val withoutBuildMetadata = normalized.substringBefore("+")
    val main = withoutBuildMetadata.substringBefore("-")
    val prerelease = withoutBuildMetadata.substringAfter("-", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
    val segments = AppVersionNumberRegex.findAll(main)
        .map { match -> match.value.toLongOrNull() ?: Long.MAX_VALUE }
        .toList()
        .ifEmpty { listOf(0L) }
    return ParsedAppVersion(
        segments = segments,
        prerelease = prerelease,
    )
}

private fun compareParsedAppVersions(left: ParsedAppVersion, right: ParsedAppVersion): Int {
    val maxSize = maxOf(left.segments.size, right.segments.size)
    for (index in 0 until maxSize) {
        val leftValue = left.segments.getOrElse(index) { 0L }
        val rightValue = right.segments.getOrElse(index) { 0L }
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return when {
        left.prerelease == null && right.prerelease != null -> 1
        left.prerelease != null && right.prerelease == null -> -1
        else -> 0
    }
}

private val AppVersionNumberRegex = Regex("\\d+")
