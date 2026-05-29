package top.iwesley.lyn.music.domain

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath
import io.ktor.http.parseUrl
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.iwesley.lyn.music.core.model.DiagnosticLogLevel
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.ImportScanReport
import top.iwesley.lyn.music.core.model.ImportScanPhase
import top.iwesley.lyn.music.core.model.ImportScanProgress
import top.iwesley.lyn.music.core.model.ImportScanProgressSink
import top.iwesley.lyn.music.core.model.ImportSource
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.ImportedTrackCandidate
import top.iwesley.lyn.music.core.model.LyricsDocument
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsLine
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NavidromeAudioQuality
import top.iwesley.lyn.music.core.model.NavidromeSourceDraft
import top.iwesley.lyn.music.core.model.NonNavidromeAudioScanResult
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.RequestMethod
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.SubsonicAuthMode
import top.iwesley.lyn.music.core.model.SubsonicSourceDraft
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildSubsonicCompatibleCoverLocator
import top.iwesley.lyn.music.core.model.buildSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.core.model.classifyAudioExtensionForImport
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleCoverLocator
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.core.model.unsupportedAudioImportFailure
import top.iwesley.lyn.music.data.db.LynMusicDatabase

const val NAVIDROME_LYRICS_SOURCE_ID = "navidrome-lyrics"
const val SUBSONIC_LYRICS_SOURCE_ID = "subsonic-lyrics"

private const val SUBSONIC_CLIENT_NAME = "LynMusic"
private const val SUBSONIC_API_VERSION = "1.16.1"
private val subsonicJson = Json { ignoreUnknownKeys = true }

data class NavidromeResolvedSource(
    val baseUrl: String,
    val wanBaseUrl: String? = null,
    val sourceId: String? = null,
    val addressSelector: RemoteSourceAddressSelector? = null,
    val username: String,
    val password: String,
    val authMode: SubsonicAuthMode = SubsonicAuthMode.PASSWORD,
    val sourceType: ImportSourceType = ImportSourceType.NAVIDROME,
) {
    val displayName: String
        get() = if (sourceType == ImportSourceType.SUBSONIC) "Subsonic" else "Navidrome"

    val logTag: String
        get() = displayName
}

private fun prepareSubsonicResolvedSource(
    baseUrl: String,
    username: String,
    credential: String,
    authMode: SubsonicAuthMode,
    sourceType: ImportSourceType,
    serverLabel: String,
): NavidromeResolvedSource {
    val normalizedCredential = credential.trim().takeIf { authMode == SubsonicAuthMode.API_KEY } ?: credential
    when (authMode) {
        SubsonicAuthMode.PASSWORD -> {
            require(username.isNotBlank()) { "请填写 $serverLabel 用户名。" }
            require(credential.isNotBlank()) { "请填写 $serverLabel 密码。" }
        }

        SubsonicAuthMode.API_KEY -> {
            require(normalizedCredential.isNotBlank()) { "请填写 $serverLabel API Key。" }
        }
    }
    return NavidromeResolvedSource(
        baseUrl = baseUrl,
        username = username.trim(),
        password = normalizedCredential,
        authMode = authMode,
        sourceType = sourceType,
    )
}

data class NavidromeSongCandidate(
    val songId: String,
    val title: String,
    val artistName: String?,
    val albumTitle: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val sizeBytes: Long,
    val suffix: String?,
    val coverArtId: String?,
    val bitDepth: Int?,
    val samplingRate: Int?,
    val bitRate: Int?,
    val channelCount: Int?,
)

fun normalizeNavidromeBaseUrl(rawUrl: String?): String {
    return normalizeSubsonicBaseUrl(rawUrl, serverLabel = "Navidrome")
}

fun normalizeSubsonicBaseUrl(rawUrl: String?): String {
    return normalizeSubsonicBaseUrl(rawUrl, serverLabel = "Subsonic")
}

private fun normalizeSubsonicBaseUrl(rawUrl: String?, serverLabel: String): String {
    val value = rawUrl.orEmpty().trim()
    require(value.isNotBlank()) { "请填写 $serverLabel 服务器地址。" }
    require('?' !in value && '#' !in value) { "$serverLabel 地址不能包含 query 或 fragment。" }
    val parsed = parseUrl(value) ?: error("$serverLabel 地址无效。")
    require(parsed.protocol.name in setOf("http", "https")) { "$serverLabel 地址只支持 http 或 https。" }
    require(parsed.host.isNotBlank()) { "$serverLabel 地址缺少主机名。" }
    require(parsed.user == null && parsed.password == null) { "请不要在 $serverLabel URL 中内嵌用户名或密码。" }
    val pathSegments = parsed.encodedPath
        .split('/')
        .filter { it.isNotBlank() }
        .map { it.decodeURLPart() }
        .let { segments ->
            if (segments.lastOrNull()?.equals("rest", ignoreCase = true) == true) {
                segments.dropLast(1)
            } else {
                segments
            }
        }
    val normalizedPath = URLBuilder().apply {
        encodedPath = "/"
        if (pathSegments.isNotEmpty()) {
            appendPathSegments(pathSegments)
        }
    }.encodedPath.removeSuffix("/").ifBlank { "/" }
    return URLBuilder(parsed).apply {
        encodedUser = null
        encodedPassword = null
        encodedParameters.clear()
        encodedFragment = ""
        encodedPath = normalizedPath
        if (port == protocol.defaultPort) {
            port = DEFAULT_PORT
        }
    }.buildString().removeSuffix("/")
}

fun buildNavidromeStreamUrl(
    baseUrl: String,
    username: String,
    password: String,
    songId: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = password,
        authMode = SubsonicAuthMode.PASSWORD,
        endpoint = "stream",
        parameters = buildMap {
            put("id", songId)
            audioQuality.maxBitRateKbps?.let { maxBitRate ->
                put("maxBitRate", maxBitRate.toString())
                put("format", "mp3")
            }
        },
        includeJsonFormat = false,
    )
}

fun buildSubsonicStreamUrl(
    baseUrl: String,
    username: String,
    credential: String,
    authMode: SubsonicAuthMode,
    songId: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = credential,
        authMode = authMode,
        endpoint = "stream",
        parameters = buildMap {
            put("id", songId)
            audioQuality.maxBitRateKbps?.let { maxBitRate ->
                put("maxBitRate", maxBitRate.toString())
                put("format", "mp3")
            }
        },
        includeJsonFormat = false,
    )
}

fun buildNavidromeDownloadUrl(
    baseUrl: String,
    username: String,
    password: String,
    songId: String,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = password,
        authMode = SubsonicAuthMode.PASSWORD,
        endpoint = "download",
        parameters = mapOf("id" to songId),
        includeJsonFormat = false,
    )
}

fun buildSubsonicDownloadUrl(
    baseUrl: String,
    username: String,
    credential: String,
    authMode: SubsonicAuthMode,
    songId: String,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = credential,
        authMode = authMode,
        endpoint = "download",
        parameters = mapOf("id" to songId),
        includeJsonFormat = false,
    )
}

fun buildNavidromeCoverArtUrl(
    baseUrl: String,
    username: String,
    password: String,
    coverArtId: String,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = password,
        authMode = SubsonicAuthMode.PASSWORD,
        endpoint = "getCoverArt",
        parameters = mapOf("id" to coverArtId),
        includeJsonFormat = false,
    )
}

fun buildSubsonicCoverArtUrl(
    baseUrl: String,
    username: String,
    credential: String,
    authMode: SubsonicAuthMode,
    coverArtId: String,
): String {
    return buildSubsonicRestUrl(
        baseUrl = baseUrl,
        username = username,
        credential = credential,
        authMode = authMode,
        endpoint = "getCoverArt",
        parameters = mapOf("id" to coverArtId),
        includeJsonFormat = false,
    )
}

suspend fun scanNavidromeLibrary(
    draft: NavidromeSourceDraft,
    sourceId: String,
    httpClient: LyricsHttpClient,
    supportedImportExtensions: Set<String>,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    progressSink: ImportScanProgressSink = ImportScanProgressSink.NoOp,
    timeoutMillis: Long? = null,
): ImportScanReport {
    val baseUrl = normalizeNavidromeBaseUrl(draft.baseUrl)
    require(draft.username.isNotBlank()) { "请填写 Navidrome 用户名。" }
    require(draft.password.isNotBlank()) { "请填写 Navidrome 密码。" }
    val source = NavidromeResolvedSource(
        baseUrl = baseUrl,
        username = draft.username.trim(),
        password = draft.password,
        authMode = SubsonicAuthMode.PASSWORD,
        sourceType = ImportSourceType.NAVIDROME,
    )
    val totalTrackCount = requestNavidromeNativeSongCount(
        httpClient = httpClient,
        source = source,
        logger = logger,
        timeoutMillis = timeoutMillis,
    )
    return scanSubsonicCompatibleLibrary(
        sourceId = sourceId,
        source = source,
        httpClient = httpClient,
        supportedImportExtensions = supportedImportExtensions,
        logger = logger,
        progressSink = progressSink,
        timeoutMillis = timeoutMillis,
        totalTrackCount = totalTrackCount,
    )
}

suspend fun scanSubsonicLibrary(
    draft: SubsonicSourceDraft,
    sourceId: String,
    httpClient: LyricsHttpClient,
    supportedImportExtensions: Set<String>,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    progressSink: ImportScanProgressSink = ImportScanProgressSink.NoOp,
    timeoutMillis: Long? = null,
): ImportScanReport {
    val baseUrl = normalizeSubsonicBaseUrl(draft.baseUrl)
    val resolved = prepareSubsonicResolvedSource(
        baseUrl = baseUrl,
        username = draft.username,
        credential = draft.credential,
        authMode = draft.authMode,
        sourceType = ImportSourceType.SUBSONIC,
        serverLabel = "Subsonic",
    )
    return scanSubsonicCompatibleLibrary(
        sourceId = sourceId,
        source = resolved,
        httpClient = httpClient,
        supportedImportExtensions = supportedImportExtensions,
        logger = logger,
        progressSink = progressSink,
        timeoutMillis = timeoutMillis,
    )
}

private suspend fun scanSubsonicCompatibleLibrary(
    sourceId: String,
    source: NavidromeResolvedSource,
    httpClient: LyricsHttpClient,
    supportedImportExtensions: Set<String>,
    logger: DiagnosticLogger,
    progressSink: ImportScanProgressSink,
    timeoutMillis: Long? = null,
    totalTrackCount: Int? = null,
): ImportScanReport {
    val artistIds = requestNavidromeArtistIds(httpClient, source, timeoutMillis)
    val tracks = mutableListOf<ImportedTrackCandidate>()
    val failures = mutableListOf<top.iwesley.lyn.music.core.model.ImportScanFailure>()
    val seenAlbumIds = linkedSetOf<String>()
    val seenSongIds = linkedSetOf<String>()
    var discoveredAudioFileCount = 0
    artistIds.forEach { artistId ->
        requestNavidromeAlbumIds(httpClient, source, artistId, timeoutMillis).forEach { albumId ->
            if (!seenAlbumIds.add(albumId)) return@forEach
            requestNavidromeAlbumSongs(httpClient, source, albumId, timeoutMillis).forEach { candidate ->
                if (candidate.songId.isNotBlank() && !seenSongIds.add(candidate.songId)) {
                    return@forEach
                }
                discoveredAudioFileCount += 1
                when (classifyAudioExtensionForImport(candidate.suffix, supportedImportExtensions)) {
                    NonNavidromeAudioScanResult.IMPORT_SUPPORTED -> {
                        tracks += candidate.toImportedTrackCandidate(sourceId, source.sourceType)
                        progressSink.onProgress(
                            ImportScanProgress(
                                sourceId = sourceId,
                                phase = ImportScanPhase.Scanning,
                                importedTrackCount = tracks.size,
                                totalTrackCount = totalTrackCount,
                            ),
                        )
                    }

                    NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
                    NonNavidromeAudioScanResult.NOT_AUDIO -> {
                        failures += unsupportedAudioImportFailure(candidate.relativePath())
                    }
                }
            }
        }
    }
    logger.log(
        level = DiagnosticLogLevel.INFO,
        tag = source.logTag,
        message = "scan-complete source=$sourceId baseUrl=${source.baseUrl} artists=${artistIds.size} discovered=$discoveredAudioFileCount imported=${tracks.size} failures=${failures.size}",
    )
    return ImportScanReport(
        tracks = tracks,
        warnings = if (discoveredAudioFileCount == 0) listOf("当前 ${source.displayName} 账号下没有可同步的歌曲。") else emptyList(),
        discoveredAudioFileCount = discoveredAudioFileCount,
        failures = failures,
        totalTrackCount = totalTrackCount,
    )
}

private suspend fun requestNavidromeNativeSongCount(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    logger: DiagnosticLogger,
    timeoutMillis: Long? = null,
): Int? {
    return runCatching {
        val loginResponse = httpClient.request(
            LyricsRequest(
                method = RequestMethod.POST,
                url = buildNavidromeNativeUrl(source.baseUrl, "auth", "login"),
                headers = mapOf("Content-Type" to "application/json"),
                body = subsonicJson.encodeToString(
                    mapOf(
                        "username" to source.username,
                        "password" to source.password,
                    ),
                ),
                timeoutMillis = timeoutMillis,
            ),
        ).getOrElse { throwable ->
            logger.logNavidromeNativeSongCountFailure(source, "登录请求失败: ${throwable.message.orEmpty()}", throwable)
            return@runCatching null
        }
        if (loginResponse.statusCode !in 200..299) {
            logger.logNavidromeNativeSongCountFailure(source, "登录失败，HTTP ${loginResponse.statusCode}")
            return@runCatching null
        }
        val loginPayload = subsonicJson.parseToJsonElement(loginResponse.body) as? JsonObject
        val token = loginPayload?.string("token")
        if (token.isNullOrBlank()) {
            logger.logNavidromeNativeSongCountFailure(source, "登录响应缺少 token")
            return@runCatching null
        }

        val countResponse = httpClient.request(
            LyricsRequest(
                method = RequestMethod.GET,
                url = buildNavidromeNativeUrl(
                    source.baseUrl,
                    "api",
                    "song",
                    queryParameters = mapOf("_start" to "0", "_end" to "1"),
                ),
                headers = mapOf("X-ND-Authorization" to "Bearer $token"),
                timeoutMillis = timeoutMillis,
            ),
        ).getOrElse { throwable ->
            logger.logNavidromeNativeSongCountFailure(source, "歌曲总数请求失败: ${throwable.message.orEmpty()}", throwable)
            return@runCatching null
        }
        if (countResponse.statusCode !in 200..299) {
            logger.logNavidromeNativeSongCountFailure(source, "歌曲总数请求失败，HTTP ${countResponse.statusCode}")
            return@runCatching null
        }
        val rawTotal = countResponse.headers.headerIgnoreCase("X-Total-Count")
        val total = rawTotal?.toIntOrNull()
        if (total == null) {
            logger.logNavidromeNativeSongCountFailure(source, "歌曲总数响应缺少有效 X-Total-Count")
        }
        total
    }.getOrElse { throwable ->
        logger.logNavidromeNativeSongCountFailure(source, "歌曲总数探测失败: ${throwable.message.orEmpty()}", throwable)
        null
    }
}

private fun buildNavidromeNativeUrl(
    baseUrl: String,
    vararg pathSegments: String,
    queryParameters: Map<String, String> = emptyMap(),
): String {
    val parsed = parseUrl(normalizeNavidromeBaseUrl(baseUrl)) ?: error("Navidrome 地址无效。")
    return URLBuilder(parsed).apply {
        appendPathSegments(pathSegments.toList())
        queryParameters.forEach { (key, value) -> parameters.append(key, value) }
    }.buildString()
}

private fun Map<String, String>.headerIgnoreCase(name: String): String? {
    return entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

private fun DiagnosticLogger.logNavidromeNativeSongCountFailure(
    source: NavidromeResolvedSource,
    message: String,
    throwable: Throwable? = null,
) {
    if (this === NoopDiagnosticLogger) return
    log(
        level = DiagnosticLogLevel.WARN,
        tag = source.logTag,
        message = "native-song-count $message",
        throwable = throwable,
    )
}

suspend fun testNavidromeConnection(
    draft: NavidromeSourceDraft,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    timeoutMillis: Long? = null,
) {
    require(draft.username.isNotBlank()) { "请填写 Navidrome 用户名。" }
    require(draft.password.isNotBlank()) { "请填写 Navidrome 密码。" }
    val resolved = NavidromeResolvedSource(
        baseUrl = normalizeNavidromeBaseUrl(draft.baseUrl),
        username = draft.username.trim(),
        password = draft.password,
        authMode = SubsonicAuthMode.PASSWORD,
        sourceType = ImportSourceType.NAVIDROME,
    )
    requestNavidromeJson(
        httpClient = httpClient,
        source = resolved,
        endpoint = "ping",
        logger = logger,
        timeoutMillis = timeoutMillis,
    )
}

suspend fun testSubsonicConnection(
    draft: SubsonicSourceDraft,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    timeoutMillis: Long? = null,
) {
    val resolved = prepareSubsonicResolvedSource(
        baseUrl = normalizeSubsonicBaseUrl(draft.baseUrl),
        username = draft.username,
        credential = draft.credential,
        authMode = draft.authMode,
        sourceType = ImportSourceType.SUBSONIC,
        serverLabel = "Subsonic",
    )
    requestNavidromeJson(
        httpClient = httpClient,
        source = resolved,
        endpoint = "ping",
        logger = logger,
        timeoutMillis = timeoutMillis,
    )
}

suspend fun requestNavidromeLyrics(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    track: Track,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): LyricsDocument? {
    requestNavidromeStructuredLyrics(
        httpClient = httpClient,
        source = source,
        track = track,
        logger = logger,
    )?.let { return it }
    val payload = requestNavidromeLyricsPayload(
        httpClient = httpClient,
        source = source,
        title = track.title,
        artistName = track.artistName,
        logger = logger,
    ) ?: return null
    val lines = parseLrc(payload).ifEmpty { parsePlainText(payload) }
    if (lines.isEmpty()) return null
    val document = LyricsDocument(
        lines = lines,
        offsetMs = 0L,
        sourceId = lyricsSourceIdFor(source.sourceType),
        rawPayload = payload,
    )
    logger.log(
        level = DiagnosticLogLevel.INFO,
        tag = source.logTag,
        message = buildString {
            append("lyrics-resolved ")
            append(formatNavidromeLyricsContext(track.title, track.artistName))
            append(" synced=")
            append(document.isSynced)
            append(" lines=")
            append(document.lines.size)
            append('\n')
            append("lyrics-payload:\n")
            append(payload.ifBlank { "<empty>" })
        },
    )
    return document
}

private suspend fun requestNavidromeStructuredLyrics(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    track: Track,
    logger: DiagnosticLogger,
): LyricsDocument? {
    val locator = parseSubsonicCompatibleSongLocator(track.mediaLocator) ?: return null
    val songId = locator.itemId
    val response = runCatching {
        requestNavidromeJson(
            httpClient = httpClient,
            source = source,
            endpoint = "getLyricsBySongId",
            parameters = mapOf(
                "id" to songId,
                "enhanced" to "true",
            ),
            logger = logger,
            logContext = buildString {
                append("songId=\"")
                append(songId)
                append("\" ")
                append(formatNavidromeLyricsContext(track.title, track.artistName))
            },
        )
    }.getOrElse { throwable ->
        logger.log(
            level = DiagnosticLogLevel.WARN,
            tag = source.logTag,
            message = buildString {
                append("structured-lyrics-fallback ")
                append("songId=\"")
                append(songId)
                append("\" ")
                append(formatNavidromeLyricsContext(track.title, track.artistName))
                append(" cause=")
                append(throwable.message.orEmpty().ifBlank { throwable::class.simpleName.orEmpty() })
            },
            throwable = throwable,
        )
        return null
    }
    val document = parseNavidromeStructuredLyricsDocument(
        payload = response,
        sourceId = lyricsSourceIdFor(source.sourceType),
    )
        ?.takeIf { it.lines.isNotEmpty() }
        ?: return null
    logger.log(
        level = DiagnosticLogLevel.INFO,
        tag = source.logTag,
        message = buildString {
            append("lyrics-resolved endpoint=getLyricsBySongId ")
            append("songId=\"")
            append(songId)
            append("\" ")
            append(formatNavidromeLyricsContext(track.title, track.artistName))
            append(" synced=")
            append(document.isSynced)
            append(" lines=")
            append(document.lines.size)
            append(" offsetMs=")
            append(document.offsetMs)
            append('\n')
            append("lyrics-payload:\n")
            append(document.rawPayload.ifBlank { "<empty>" })
        },
    )
    return document
}

suspend fun resolveNavidromeStreamUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    return resolveSubsonicCompatibleStreamUrl(database, secureCredentialStore, locator, audioQuality, addressSelector)
}

suspend fun resolveNavidromeStreamUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    return resolveSubsonicCompatibleStreamUrlCandidates(database, secureCredentialStore, locator, audioQuality, addressSelector)
}

suspend fun resolveSubsonicCompatibleStreamUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    val parsed = parseSubsonicCompatibleSongLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return buildSubsonicStreamUrl(
        baseUrl = source.preferredBaseUrl(),
        username = source.username,
        credential = source.password,
        authMode = source.authMode,
        songId = parsed.itemId,
        audioQuality = audioQuality,
    )
}

suspend fun resolveSubsonicCompatibleStreamUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    audioQuality: NavidromeAudioQuality = NavidromeAudioQuality.Original,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    val parsed = parseSubsonicCompatibleSongLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return source.addressCandidates().map { candidate ->
        RemoteSourceResolvedUrl(
            sourceId = source.sourceId.orEmpty(),
            kind = candidate.kind,
            value = buildSubsonicStreamUrl(
                baseUrl = candidate.value,
                username = source.username,
                credential = source.password,
                authMode = source.authMode,
                songId = parsed.itemId,
                audioQuality = audioQuality,
            ),
        )
    }
}

suspend fun resolveNavidromeDownloadUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    return resolveSubsonicCompatibleDownloadUrl(database, secureCredentialStore, locator, addressSelector)
}

suspend fun resolveNavidromeDownloadUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    return resolveSubsonicCompatibleDownloadUrlCandidates(database, secureCredentialStore, locator, addressSelector)
}

suspend fun resolveSubsonicCompatibleDownloadUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    val parsed = parseSubsonicCompatibleSongLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return buildSubsonicDownloadUrl(
        baseUrl = source.preferredBaseUrl(),
        username = source.username,
        credential = source.password,
        authMode = source.authMode,
        songId = parsed.itemId,
    )
}

suspend fun resolveSubsonicCompatibleDownloadUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    val parsed = parseSubsonicCompatibleSongLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return source.addressCandidates().map { candidate ->
        RemoteSourceResolvedUrl(
            sourceId = source.sourceId.orEmpty(),
            kind = candidate.kind,
            value = buildSubsonicDownloadUrl(
                baseUrl = candidate.value,
                username = source.username,
                credential = source.password,
                authMode = source.authMode,
                songId = parsed.itemId,
            ),
        )
    }
}

suspend fun resolveNavidromeCoverArtUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    return resolveSubsonicCompatibleCoverArtUrl(database, secureCredentialStore, locator, addressSelector)
}

suspend fun resolveNavidromeCoverArtUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    return resolveSubsonicCompatibleCoverArtUrlCandidates(database, secureCredentialStore, locator, addressSelector)
}

suspend fun resolveSubsonicCompatibleCoverArtUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): String? {
    val parsed = parseSubsonicCompatibleCoverLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return buildSubsonicCoverArtUrl(
        baseUrl = source.preferredBaseUrl(),
        username = source.username,
        credential = source.password,
        authMode = source.authMode,
        coverArtId = parsed.itemId,
    )
}

suspend fun resolveSubsonicCompatibleCoverArtUrlCandidates(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): List<RemoteSourceResolvedUrl>? {
    val parsed = parseSubsonicCompatibleCoverLocator(locator) ?: return null
    val source = resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector) ?: return null
    return source.addressCandidates().map { candidate ->
        RemoteSourceResolvedUrl(
            sourceId = source.sourceId.orEmpty(),
            kind = candidate.kind,
            value = buildSubsonicCoverArtUrl(
                baseUrl = candidate.value,
                username = source.username,
                credential = source.password,
                authMode = source.authMode,
                coverArtId = parsed.itemId,
            ),
        )
    }
}

private suspend fun resolveNavidromeSource(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): NavidromeResolvedSource? {
    return resolveSubsonicCompatibleSource(database, secureCredentialStore, locator, addressSelector)
        ?.takeIf { it.sourceType == ImportSourceType.NAVIDROME }
}

private suspend fun resolveSubsonicCompatibleSource(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
    addressSelector: RemoteSourceAddressSelector = RemoteSourceAddressSelector(),
): NavidromeResolvedSource? {
    val parsed = parseSubsonicCompatibleSongLocator(locator)
        ?: parseSubsonicCompatibleCoverLocator(locator)
        ?: return null
    val entity = database.importSourceDao().getById(parsed.sourceId)
        ?.takeIf { it.type == parsed.sourceType.name && it.enabled }
        ?: return null
    val sourceType = runCatching { ImportSourceType.valueOf(entity.type) }.getOrNull() ?: return null
    if (sourceType !in subsonicCompatibleSourceTypes) return null
    val authMode = entity.authMode.toSubsonicAuthMode()
    val username = entity.username?.trim().orEmpty()
    val credential = entity.credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
    if (authMode == SubsonicAuthMode.PASSWORD && (username.isBlank() || credential.isBlank())) return null
    if (authMode == SubsonicAuthMode.API_KEY && credential.isBlank()) return null
    val serverLabel = if (sourceType == ImportSourceType.NAVIDROME) "Navidrome" else "Subsonic"
    val (lanBaseUrl, wanBaseUrl) = normalizeRemoteSourceBaseUrls(
        sourceType = sourceType,
        lanBaseUrl = entity.rootReference,
        wanBaseUrl = entity.wanRootReference,
        normalizeBaseUrl = { normalizeSubsonicBaseUrl(rawUrl = it, serverLabel = serverLabel) },
    )
    return NavidromeResolvedSource(
        baseUrl = lanBaseUrl,
        wanBaseUrl = wanBaseUrl,
        sourceId = entity.id,
        addressSelector = addressSelector,
        username = username,
        password = credential,
        authMode = authMode,
        sourceType = sourceType,
    )
}

private suspend fun requestNavidromeArtistIds(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    timeoutMillis: Long? = null,
): List<String> {
    val response = requestNavidromeJson(
        httpClient = httpClient,
        source = source,
        endpoint = "getArtists",
        timeoutMillis = timeoutMillis,
    )
    return response["artists"].asObject("artists")["index"].asObjectList()
        .flatMap { index -> index["artist"].asObjectList() }
        .mapNotNull { artist -> artist.string("id") }
}

private suspend fun requestNavidromeAlbumIds(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    artistId: String,
    timeoutMillis: Long? = null,
): List<String> {
    val response = requestNavidromeJson(
        httpClient = httpClient,
        source = source,
        endpoint = "getArtist",
        parameters = mapOf("id" to artistId),
        timeoutMillis = timeoutMillis,
    )
    return response["artist"].asObject("artist")["album"].asObjectList()
        .mapNotNull { album -> album.string("id") }
}

private suspend fun requestNavidromeAlbumSongs(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    albumId: String,
    timeoutMillis: Long? = null,
): List<NavidromeSongCandidate> {
    val response = requestNavidromeJson(
        httpClient = httpClient,
        source = source,
        endpoint = "getAlbum",
        parameters = mapOf("id" to albumId),
        timeoutMillis = timeoutMillis,
    )
    val album = response["album"].asObject("album")
    val albumTitle = album.string("name") ?: album.string("title") ?: album.string("album")
    val albumArtist = album.string("artist")
    val albumCoverArtId = album.string("coverArt")
    return album["song"].asObjectList()
        .map { song ->
            val songId = song.string("id").orEmpty()
            val suffix = song.string("suffix")
            val title = song.string("title").orEmpty().ifBlank { "未知曲目" }
            val artistName = song.string("artist") ?: albumArtist
            val coverArtId = song.string("coverArt") ?: albumCoverArtId
            NavidromeSongCandidate(
                songId = songId,
                title = title,
                artistName = artistName,
                albumTitle = song.string("album") ?: albumTitle,
                durationMs = (song.long("duration") ?: 0L) * 1_000L,
                trackNumber = song.int("track"),
                discNumber = song.int("discNumber"),
                sizeBytes = song.long("size") ?: 0L,
                suffix = suffix,
                coverArtId = coverArtId,
                bitDepth = song.int("bitDepth"),
                samplingRate = song.int("samplingRate"),
                bitRate = song.int("bitRate"),
                channelCount = song.int("channelCount"),
            )
        }
}

private suspend fun requestNavidromeLyricsPayload(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    title: String,
    artistName: String?,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): String? {
    if (title.isBlank()) return null
    val response = requestNavidromeJson(
        httpClient = httpClient,
        source = source,
        endpoint = "getLyrics",
        parameters = buildMap {
            put("title", title)
            artistName?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        },
        logger = logger,
        logContext = formatNavidromeLyricsContext(title, artistName),
    )
    return response["lyrics"].asObjectOrNull()
        ?.string("value")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal suspend fun requestNavidromeJson(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    endpoint: String,
    parameters: Map<String, String> = emptyMap(),
    logger: DiagnosticLogger = NoopDiagnosticLogger,
    logContext: String? = null,
    timeoutMillis: Long? = null,
): JsonObject {
    val selector = source.addressSelector
    val sourceId = source.sourceId
    if (selector != null && sourceId != null) {
        return selector.withAddressFallback(
            sourceId = sourceId,
            sourceType = source.sourceType,
            lanBaseUrl = source.baseUrl,
            wanBaseUrl = source.wanBaseUrl,
            normalizeBaseUrl = ::normalizeSubsonicBaseUrl,
        ) { candidate ->
            requestNavidromeJsonWithoutAddressFallback(
                httpClient = httpClient,
                source = source.copy(
                    baseUrl = candidate.value,
                    wanBaseUrl = null,
                    addressSelector = null,
                ),
                endpoint = endpoint,
                parameters = parameters,
                logger = logger,
                logContext = logContext,
                timeoutMillis = timeoutMillis,
            )
        }
    }
    return requestNavidromeJsonWithoutAddressFallback(
        httpClient = httpClient,
        source = source,
        endpoint = endpoint,
        parameters = parameters,
        logger = logger,
        logContext = logContext,
        timeoutMillis = timeoutMillis,
    )
}

private suspend fun requestNavidromeJsonWithoutAddressFallback(
    httpClient: LyricsHttpClient,
    source: NavidromeResolvedSource,
    endpoint: String,
    parameters: Map<String, String>,
    logger: DiagnosticLogger,
    logContext: String?,
    timeoutMillis: Long? = null,
): JsonObject {
    val request = LyricsRequest(
        method = RequestMethod.GET,
        url = buildSubsonicRestUrl(
            baseUrl = source.baseUrl,
            username = source.username,
            credential = source.password,
            authMode = source.authMode,
            endpoint = endpoint,
            parameters = parameters,
            includeJsonFormat = true,
        ),
        timeoutMillis = timeoutMillis,
    )
    if (logger !== NoopDiagnosticLogger) {
        logger.log(
            level = DiagnosticLogLevel.INFO,
            tag = source.logTag,
            message = buildString {
                append("request endpoint=")
                append(endpoint)
                logContext?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
                append('\n')
                append("url: ")
                append(redactSubsonicUrlForLog(request.url))
            },
        )
    }
    val response = httpClient.request(request).getOrElse { throwable ->
        throw IllegalStateException("${source.displayName} $endpoint 请求失败: ${throwable.message.orEmpty()}", throwable)
    }
    if (logger !== NoopDiagnosticLogger) {
        logger.log(
            level = DiagnosticLogLevel.INFO,
            tag = source.logTag,
            message = buildString {
                append("response endpoint=")
                append(endpoint)
                logContext?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
                append(" status=")
                append(response.statusCode)
                append('\n')
                append("body:\n")
                append(response.body.ifBlank { "<empty>" })
            },
        )
    }
    require(response.statusCode in 200..299) { "${source.displayName} $endpoint 失败，HTTP ${response.statusCode}" }
    val root = subsonicJson.parseToJsonElement(response.body) as? JsonObject
        ?: error("${source.displayName} $endpoint 返回不是 JSON 对象。")
    val payload = root["subsonic-response"].asObject("subsonic-response")
    val status = payload.string("status").orEmpty()
    if (!status.equals("ok", ignoreCase = true)) {
        val error = payload["error"].asObjectOrNull()
        val message = error?.string("message").orEmpty().ifBlank { "${source.displayName} $endpoint 返回失败状态。" }
        error(message)
    }
    return payload
}

private fun NavidromeResolvedSource.preferredBaseUrl(): String {
    val selector = addressSelector
    val sourceId = sourceId
    if (selector != null && sourceId != null) {
        return selector.orderedBaseUrls(
            sourceId = sourceId,
            sourceType = sourceType,
            lanBaseUrl = baseUrl,
            wanBaseUrl = wanBaseUrl,
            normalizeBaseUrl = ::normalizeSubsonicBaseUrl,
        ).first().value
    }
    return normalizeSubsonicBaseUrl(baseUrl)
}

private fun NavidromeResolvedSource.addressCandidates(): List<RemoteSourceBaseUrl> {
    val selector = addressSelector
    val sourceId = sourceId
    if (selector != null && sourceId != null) {
        return selector.orderedBaseUrls(
            sourceId = sourceId,
            sourceType = sourceType,
            lanBaseUrl = baseUrl,
            wanBaseUrl = wanBaseUrl,
            normalizeBaseUrl = ::normalizeSubsonicBaseUrl,
        )
    }
    return listOf(RemoteSourceBaseUrl(RemoteSourceAddressKind.LAN, normalizeSubsonicBaseUrl(baseUrl)))
}

private fun formatNavidromeLyricsContext(
    title: String,
    artistName: String?,
): String {
    return buildString {
        append("title=\"")
        append(title)
        append('"')
        artistName?.takeIf { it.isNotBlank() }?.let {
            append(" artist=\"")
            append(it)
            append('"')
        }
    }
}

private fun redactSubsonicUrlForLog(url: String): String {
    return url
        .replace(Regex("([?&]t=)[^&]*"), "$1<redacted>")
        .replace(Regex("([?&]s=)[^&]*"), "$1<redacted>")
        .replace(Regex("([?&]apiKey=)[^&]*"), "$1<redacted>")
        .replace(Regex("([?&]p=)[^&]*"), "$1<redacted>")
}

private fun parseNavidromeStructuredLyricsDocument(payload: JsonObject): LyricsDocument? {
    return parseNavidromeStructuredLyricsDocument(payload, NAVIDROME_LYRICS_SOURCE_ID)
}

private fun parseNavidromeStructuredLyricsDocument(
    payload: JsonObject,
    sourceId: String,
): LyricsDocument? {
    val parsed = parseNavidromeStructuredLyricsPayload(payload)
        ?: return null
    return LyricsDocument(
        lines = parsed.lines,
        offsetMs = parsed.offsetMs,
        sourceId = sourceId,
        rawPayload = payload.toString(),
    )
}

private fun buildSubsonicRestUrl(
    baseUrl: String,
    username: String,
    credential: String,
    authMode: SubsonicAuthMode,
    endpoint: String,
    parameters: Map<String, String>,
    includeJsonFormat: Boolean,
): String {
    val normalizedBaseUrl = normalizeSubsonicBaseUrl(baseUrl)
    return URLBuilder(normalizedBaseUrl).apply {
        appendPathSegments("rest", endpoint)
        parameters.forEach { (key, value) ->
            if (value.isNotBlank()) {
                this.parameters.append(key, value)
            }
        }
        when (authMode) {
            SubsonicAuthMode.PASSWORD -> {
                val salt = randomNavidromeSalt()
                val token = md5Hex(credential + salt)
                this.parameters.append("u", username)
                this.parameters.append("t", token)
                this.parameters.append("s", salt)
            }

            SubsonicAuthMode.API_KEY -> {
                this.parameters.append("apiKey", credential)
            }
        }
        this.parameters.append("v", SUBSONIC_API_VERSION)
        this.parameters.append("c", SUBSONIC_CLIENT_NAME)
        if (includeJsonFormat) {
            this.parameters.append("f", "json")
        }
    }.buildString()
}

private fun buildNavidromeRelativePath(
    artistName: String?,
    albumTitle: String?,
    title: String,
    suffix: String?,
): String {
    val fileName = buildString {
        append(normalizeNavidromePathSegment(title.ifBlank { "未知曲目" }))
        suffix?.trim()?.takeIf { it.isNotBlank() }?.let {
            append('.')
            append(it.lowercase())
        }
    }
    return listOf(
        normalizeNavidromePathSegment(artistName.orEmpty().ifBlank { "未知艺人" }),
        normalizeNavidromePathSegment(albumTitle.orEmpty().ifBlank { "未知专辑" }),
        fileName,
    ).joinToString("/")
}

private fun NavidromeSongCandidate.relativePath(): String {
    return buildNavidromeRelativePath(
        artistName = artistName,
        albumTitle = albumTitle,
        title = title,
        suffix = suffix,
    )
}

private fun NavidromeSongCandidate.toImportedTrackCandidate(
    sourceId: String,
    sourceType: ImportSourceType,
): ImportedTrackCandidate {
    return ImportedTrackCandidate(
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        mediaLocator = buildSubsonicCompatibleSongLocator(sourceType, sourceId, songId),
        relativePath = relativePath(),
        artworkLocator = coverArtId?.let { buildSubsonicCompatibleCoverLocator(sourceType, sourceId, it) },
        embeddedLyrics = null,
        sizeBytes = sizeBytes,
        modifiedAt = 0L,
        bitDepth = bitDepth,
        samplingRate = samplingRate,
        bitRate = bitRate,
        channelCount = channelCount,
    )
}

private fun normalizeNavidromePathSegment(value: String): String {
    return value.trim()
        .replace('/', '／')
        .replace('\\', '／')
        .ifBlank { "未知" }
}

private fun randomNavidromeSalt(length: Int = 12): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString(length) {
        repeat(length) {
            append(alphabet[Random.nextInt(alphabet.length)])
        }
    }
}

private val subsonicCompatibleSourceTypes = setOf(
    ImportSourceType.NAVIDROME,
    ImportSourceType.SUBSONIC,
)

fun isSubsonicCompatibleSourceType(type: ImportSourceType): Boolean {
    return type in subsonicCompatibleSourceTypes
}

fun lyricsSourceIdFor(type: ImportSourceType): String {
    return if (type == ImportSourceType.SUBSONIC) SUBSONIC_LYRICS_SOURCE_ID else NAVIDROME_LYRICS_SOURCE_ID
}

fun String?.toSubsonicAuthMode(): SubsonicAuthMode {
    return runCatching { SubsonicAuthMode.valueOf(this.orEmpty()) }
        .getOrDefault(SubsonicAuthMode.PASSWORD)
}

private fun JsonElement?.asObject(context: String): JsonObject {
    return this as? JsonObject ?: error("Navidrome $context 缺失或格式错误。")
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asObjectList(): List<JsonObject> {
    return when (val element = this) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.boolean(key: String): Boolean? {
    return string(key)?.lowercase()?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
