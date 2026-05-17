package top.iwesley.lyn.music.domain

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath
import io.ktor.http.parseUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.iwesley.lyn.music.core.model.DiagnosticLogLevel
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.EmbyCredential
import top.iwesley.lyn.music.core.model.EmbySourceDraft
import top.iwesley.lyn.music.core.model.ImportScanFailure
import top.iwesley.lyn.music.core.model.ImportScanReport
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.ImportedTrackCandidate
import top.iwesley.lyn.music.core.model.LyricsDocument
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsLine
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.NonNavidromeAudioScanResult
import top.iwesley.lyn.music.core.model.RequestMethod
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.buildEmbyCoverLocator
import top.iwesley.lyn.music.core.model.buildEmbySongLocator
import top.iwesley.lyn.music.core.model.classifyAudioExtensionForImport
import top.iwesley.lyn.music.core.model.parseEmbyCoverLocator
import top.iwesley.lyn.music.core.model.parseEmbySongLocator
import top.iwesley.lyn.music.core.model.unsupportedAudioImportFailure
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import kotlin.random.Random
import kotlin.time.Instant

private const val EMBY_CLIENT_NAME = "LynMusic"
private const val EMBY_DEVICE_NAME = "LynMusic"
private const val EMBY_VERSION = "1.0.0"
private const val EMBY_PAGE_SIZE = 200
private val embyJson = Json { ignoreUnknownKeys = true }

const val EMBY_LYRICS_SOURCE_ID = "emby-lyrics"
internal const val EMBY_DEVICE_ID_CREDENTIAL_KEY = "emby-device-id"

data class EmbyResolvedSource(
    val sourceId: String,
    val baseUrl: String,
    val credential: EmbyCredential,
    val deviceId: String,
)

data class EmbyFavoriteItem(
    val itemId: String,
    val favoritedAt: Long?,
)

data class EmbyPlaylistSummaryPayload(
    val id: String,
    val name: String,
)

data class EmbyPlaylistEntryPayload(
    val itemId: String,
    val playlistItemId: String?,
)

data class EmbyRecentTrackPayload(
    val itemId: String,
    val albumId: String?,
    val playedAt: Long?,
    val playCount: Int?,
)

fun normalizeEmbyBaseUrl(rawUrl: String?): String {
    val value = rawUrl.orEmpty().trim()
    require(value.isNotBlank()) { "请填写 Emby 服务器地址。" }
    require('?' !in value && '#' !in value) { "Emby 地址不能包含 query 或 fragment。" }
    val parsed = parseUrl(value) ?: error("Emby 地址无效。")
    require(parsed.protocol.name in setOf("http", "https")) { "Emby 地址只支持 http 或 https。" }
    require(parsed.host.isNotBlank()) { "Emby 地址缺少主机名。" }
    require(parsed.user == null && parsed.password == null) { "请不要在 Emby URL 中内嵌用户名或密码。" }
    val decodedSegments = parsed.encodedPath
        .split('/')
        .filter { it.isNotBlank() }
        .map { it.decodeURLPart() }
    val normalizedPath = URLBuilder().apply {
        encodedPath = "/"
        if (decodedSegments.isNotEmpty()) {
            appendPathSegments(decodedSegments)
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

suspend fun resolveEmbyDeviceId(secureCredentialStore: SecureCredentialStore): String {
    secureCredentialStore.get(EMBY_DEVICE_ID_CREDENTIAL_KEY)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val deviceId = generateEmbyDeviceId()
    secureCredentialStore.put(EMBY_DEVICE_ID_CREDENTIAL_KEY, deviceId)
    return deviceId
}

suspend fun authenticateEmby(
    draft: EmbySourceDraft,
    deviceId: String,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): EmbyCredential {
    require(draft.username.isNotBlank()) { "请填写 Emby 用户名。" }
    require(draft.password.isNotBlank()) { "请填写 Emby 密码。" }
    require(deviceId.isNotBlank()) { "Emby 设备 ID 为空。" }
    val baseUrl = normalizeEmbyBaseUrl(draft.baseUrl)
    val request = LyricsRequest(
        method = RequestMethod.POST,
        url = buildEmbyApiUrl(baseUrl, "Users", "AuthenticateByName"),
        headers = mapOf(
            "Content-Type" to "application/json",
            "X-Emby-Authorization" to embyAuthorizationHeader(deviceId = deviceId),
        ),
        body = JsonObject(
            mapOf(
                "Username" to JsonPrimitive(draft.username.trim()),
                "Pw" to JsonPrimitive(draft.password),
            ),
        ).toString(),
    )
    logEmbyRequest(logger, "AuthenticateByName", request.url)
    val response = httpClient.request(request).getOrElse { throwable ->
        throw IllegalStateException("Emby 登录请求失败: ${throwable.message.orEmpty()}", throwable)
    }
    require(response.statusCode in 200..299) { "Emby 登录失败，HTTP ${response.statusCode}" }
    val payload = parseEmbyObject(response.body, "登录")
    val token = payload.string("AccessToken") ?: error("Emby 登录响应缺少 AccessToken。")
    val userId = payload["User"].asObjectOrNull()?.string("Id") ?: error("Emby 登录响应缺少用户 ID。")
    require(token.isNotBlank()) { "Emby 登录响应 AccessToken 为空。" }
    require(userId.isNotBlank()) { "Emby 登录响应用户 ID 为空。" }
    return EmbyCredential(userId = userId, accessToken = token)
}

suspend fun testEmbyConnection(
    draft: EmbySourceDraft,
    deviceId: String,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): EmbyCredential {
    return authenticateEmby(draft, deviceId, httpClient, logger)
}

suspend fun testEmbyConnection(
    draft: EmbySourceDraft,
    credential: EmbyCredential,
    deviceId: String,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    require(credential.userId.isNotBlank()) { "Emby 用户 ID 为空。" }
    require(credential.accessToken.isNotBlank()) { "Emby token 为空。" }
    require(deviceId.isNotBlank()) { "Emby 设备 ID 为空。" }
    requestEmbyJson(
        httpClient = httpClient,
        credential = credential,
        deviceId = deviceId,
        url = buildEmbyApiUrl(
            normalizeEmbyBaseUrl(draft.baseUrl),
            "Users",
            credential.userId,
            "Items",
            parameters = mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Audio",
                "MediaTypes" to "Audio",
                "Limit" to "1",
            ),
        ),
        method = RequestMethod.GET,
        body = null,
        operation = "ConnectionTest",
        logger = logger,
    )
}

suspend fun scanEmbyLibrary(
    draft: EmbySourceDraft,
    credential: EmbyCredential,
    deviceId: String,
    sourceId: String,
    httpClient: LyricsHttpClient,
    supportedImportExtensions: Set<String>,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): ImportScanReport {
    val baseUrl = normalizeEmbyBaseUrl(draft.baseUrl)
    require(credential.userId.isNotBlank()) { "Emby 用户 ID 为空。" }
    require(credential.accessToken.isNotBlank()) { "Emby token 为空。" }
    require(deviceId.isNotBlank()) { "Emby 设备 ID 为空。" }
    val tracks = mutableListOf<ImportedTrackCandidate>()
    val failures = mutableListOf<ImportScanFailure>()
    val seenItemIds = linkedSetOf<String>()
    var discoveredAudioFileCount = 0
    var startIndex = 0
    while (true) {
        val page = requestEmbyItemsPage(
            baseUrl = baseUrl,
            credential = credential,
            deviceId = deviceId,
            startIndex = startIndex,
            httpClient = httpClient,
            logger = logger,
        )
        val items = page.items
        items.forEach { item ->
            val itemId = item.itemId
            if (itemId.isBlank() || !seenItemIds.add(itemId)) return@forEach
            discoveredAudioFileCount += 1
            val suffix = item.suffix()
            when (classifyAudioExtensionForImport(suffix, supportedImportExtensions)) {
                NonNavidromeAudioScanResult.IMPORT_SUPPORTED -> tracks += item.toImportedTrackCandidate(sourceId)
                NonNavidromeAudioScanResult.IMPORT_UNSUPPORTED,
                NonNavidromeAudioScanResult.NOT_AUDIO,
                -> failures += unsupportedAudioImportFailure(item.relativePath())
            }
        }
        val nextStart = startIndex + items.size
        if (items.isEmpty() || nextStart >= page.totalRecordCount) break
        startIndex = nextStart
    }
    logger.log(
        level = DiagnosticLogLevel.INFO,
        tag = "Emby",
        message = "scan-complete source=$sourceId baseUrl=$baseUrl discovered=$discoveredAudioFileCount imported=${tracks.size} failures=${failures.size}",
    )
    return ImportScanReport(
        tracks = tracks,
        warnings = if (discoveredAudioFileCount == 0) listOf("当前 Emby 账号下没有可同步的歌曲。") else emptyList(),
        discoveredAudioFileCount = discoveredAudioFileCount,
        failures = failures,
    )
}

fun serializeEmbyCredential(credential: EmbyCredential): String {
    return credential.userId + "\n" + credential.accessToken
}

fun parseEmbyCredential(value: String?): EmbyCredential? {
    val lines = value.orEmpty().lines()
    val userId = lines.firstOrNull()?.trim().orEmpty()
    val token = lines.drop(1).joinToString("\n").trim()
    if (userId.isBlank() || token.isBlank()) return null
    return EmbyCredential(userId = userId, accessToken = token)
}

internal suspend fun resolveEmbySource(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    sourceId: String,
): EmbyResolvedSource? {
    val source = database.importSourceDao().getById(sourceId)
        ?.takeIf { it.type == ImportSourceType.EMBY.name && it.enabled }
        ?: return null
    val credential = parseEmbyCredential(source.credentialKey?.let { secureCredentialStore.get(it) }) ?: return null
    return EmbyResolvedSource(
        sourceId = source.id,
        baseUrl = normalizeEmbyBaseUrl(source.rootReference),
        credential = credential,
        deviceId = resolveEmbyDeviceId(secureCredentialStore),
    )
}

suspend fun resolveEmbyStreamUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
): String? {
    val parsed = parseEmbySongLocator(locator) ?: return null
    val source = resolveEmbySource(database, secureCredentialStore, parsed.first) ?: return null
    return buildEmbyStreamUrl(
        baseUrl = source.baseUrl,
        itemId = parsed.second,
        accessToken = source.credential.accessToken,
    )
}

suspend fun resolveEmbyDownloadUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
): String? {
    val parsed = parseEmbySongLocator(locator) ?: return null
    val source = resolveEmbySource(database, secureCredentialStore, parsed.first) ?: return null
    return buildEmbyDownloadUrl(
        baseUrl = source.baseUrl,
        itemId = parsed.second,
        accessToken = source.credential.accessToken,
    )
}

suspend fun resolveEmbyCoverArtUrl(
    database: LynMusicDatabase,
    secureCredentialStore: SecureCredentialStore,
    locator: String,
): String? {
    val parsed = parseEmbyCoverLocator(locator) ?: return null
    val source = resolveEmbySource(database, secureCredentialStore, parsed.first) ?: return null
    return buildEmbyCoverArtUrl(
        baseUrl = source.baseUrl,
        itemId = parsed.second,
        accessToken = source.credential.accessToken,
    )
}

fun buildEmbyStreamUrl(
    baseUrl: String,
    itemId: String,
    accessToken: String,
): String {
    return URLBuilder(normalizeEmbyBaseUrl(baseUrl)).apply {
        appendPathSegments("Audio", itemId, "stream")
        parameters.append("Static", "true")
        parameters.append("api_key", accessToken)
    }.buildString()
}

fun buildEmbyDownloadUrl(
    baseUrl: String,
    itemId: String,
    accessToken: String,
): String {
    return URLBuilder(normalizeEmbyBaseUrl(baseUrl)).apply {
        appendPathSegments("Items", itemId, "Download")
        parameters.append("api_key", accessToken)
    }.buildString()
}

fun buildEmbyCoverArtUrl(
    baseUrl: String,
    itemId: String,
    accessToken: String,
): String {
    return URLBuilder(normalizeEmbyBaseUrl(baseUrl)).apply {
        appendPathSegments("Items", itemId, "Images", "Primary")
        parameters.append("api_key", accessToken)
    }.buildString()
}

internal suspend fun setEmbyFavorite(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    itemId: String,
    favorite: Boolean,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    val pathSegments = if (favorite) {
        arrayOf("Users", source.credential.userId, "FavoriteItems", itemId)
    } else {
        arrayOf("Users", source.credential.userId, "FavoriteItems", itemId, "Delete")
    }
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = pathSegments,
        operation = if (favorite) "FavoriteItems" else "FavoriteItems/Delete",
        logger = logger,
    )
}

internal suspend fun fetchEmbyFavorites(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): List<EmbyFavoriteItem> {
    return requestEmbyPagedItems(
        httpClient = httpClient,
        source = source,
        operation = "FavoriteItems",
        parameters = mapOf(
            "Recursive" to "true",
            "MediaTypes" to "Audio",
            "IncludeItemTypes" to "Audio",
            "Filters" to "IsFavorite",
            "Fields" to "UserData",
            "EnableUserData" to "true",
        ),
        logger = logger,
    ).mapNotNull { item ->
        val itemId = item.string("Id") ?: return@mapNotNull null
        EmbyFavoriteItem(
            itemId = itemId,
            favoritedAt = null,
        )
    }
}

internal suspend fun fetchEmbyPlaylists(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): List<EmbyPlaylistSummaryPayload> {
    return requestEmbyPagedItems(
        httpClient = httpClient,
        source = source,
        operation = "Playlists",
        parameters = mapOf(
            "Recursive" to "true",
            "IncludeItemTypes" to "Playlist",
            "MediaTypes" to "Audio",
        ),
        logger = logger,
    ).mapNotNull { item ->
        val id = item.string("Id") ?: return@mapNotNull null
        val name = item.string("Name")?.trim().orEmpty().ifBlank { "未命名歌单" }
        EmbyPlaylistSummaryPayload(id = id, name = name)
    }
}

internal suspend fun createEmbyPlaylist(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    name: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): EmbyPlaylistSummaryPayload {
    val payload = requestEmbyJson(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Playlists"),
        parameters = mapOf(
            "UserId" to source.credential.userId,
            "Name" to name,
            "MediaType" to "Audio",
        ),
        operation = "CreatePlaylist",
        logger = logger,
    )
    val id = payload.string("Id") ?: error("Emby 创建歌单响应缺少 Id。")
    return EmbyPlaylistSummaryPayload(
        id = id,
        name = payload.string("Name")?.trim().orEmpty().ifBlank { name },
    )
}

internal suspend fun deleteEmbyPlaylist(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.DELETE,
        pathSegments = arrayOf("Items", playlistId),
        operation = "DeletePlaylist",
        logger = logger,
    )
}

internal suspend fun updateEmbyPlaylistName(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    name: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    val currentItem = requestEmbyJson(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.GET,
        pathSegments = arrayOf("Users", source.credential.userId, "Items", playlistId),
        operation = "PlaylistItem",
        logger = logger,
    )
    val updatedItem = JsonObject(
        currentItem.toMutableMap().apply {
            put("Id", JsonPrimitive(playlistId))
            put("Name", JsonPrimitive(name))
        },
    )
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Items", playlistId),
        parameters = mapOf("ItemId" to playlistId),
        body = updatedItem.toString(),
        operation = "UpdatePlaylist",
        logger = logger,
    )
}

internal suspend fun addEmbyPlaylistItem(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    itemId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Playlists", playlistId, "Items"),
        parameters = mapOf(
            "UserId" to source.credential.userId,
            "Ids" to itemId,
        ),
        operation = "AddPlaylistItem",
        logger = logger,
    )
}

internal suspend fun removeEmbyPlaylistEntries(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    entryIds: List<String>,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    if (entryIds.isEmpty()) return
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Playlists", playlistId, "Items", "Delete"),
        parameters = mapOf("EntryIds" to entryIds.joinToString(",")),
        operation = "RemovePlaylistItems",
        logger = logger,
    )
}

internal suspend fun fetchEmbyPlaylistEntries(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): List<EmbyPlaylistEntryPayload> {
    return requestEmbyPagedPlaylistItems(
        httpClient = httpClient,
        source = source,
        playlistId = playlistId,
        logger = logger,
    ).mapNotNull { item ->
        val itemId = item.string("Id") ?: return@mapNotNull null
        EmbyPlaylistEntryPayload(
            itemId = itemId,
            playlistItemId = item.string("PlaylistItemId"),
        )
    }
}

internal suspend fun reportEmbyNowPlaying(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    itemId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Users", source.credential.userId, "PlayingItems", itemId),
        operation = "PlayingItems",
        logger = logger,
    )
}

internal suspend fun submitEmbyPlay(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    itemId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
) {
    requestEmbyUnit(
        httpClient = httpClient,
        source = source,
        method = RequestMethod.POST,
        pathSegments = arrayOf("Users", source.credential.userId, "PlayedItems", itemId),
        operation = "PlayedItems",
        logger = logger,
    )
}

internal suspend fun fetchEmbyRecentTracks(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    limit: Int,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): List<EmbyRecentTrackPayload> {
    return requestEmbyPagedItems(
        httpClient = httpClient,
        source = source,
        operation = "RecentItems",
        parameters = mapOf(
            "Recursive" to "true",
            "MediaTypes" to "Audio",
            "IncludeItemTypes" to "Audio",
            "IsPlayed" to "true",
            "SortBy" to "DatePlayed",
            "SortOrder" to "Descending",
            "Fields" to "UserData",
            "EnableUserData" to "true",
            "Limit" to limit.coerceAtLeast(1).toString(),
        ),
        logger = logger,
        pageSize = limit.coerceAtLeast(1),
        maxItems = limit.coerceAtLeast(1),
    ).mapNotNull { item ->
        val itemId = item.string("Id") ?: return@mapNotNull null
        val userData = item["UserData"].asObjectOrNull()
        val playedAt = userData?.string("LastPlayedDate")?.let(::parseEmbyTimestampMillis)
        if (playedAt == null) return@mapNotNull null
        EmbyRecentTrackPayload(
            itemId = itemId,
            albumId = item.string("AlbumId"),
            playedAt = playedAt,
            playCount = userData.int("PlayCount"),
        )
    }
}

internal suspend fun requestEmbyLyricsDocument(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    itemId: String,
    logger: DiagnosticLogger = NoopDiagnosticLogger,
): LyricsDocument? {
    val payload = requestEmbyTextOrNull(
        httpClient = httpClient,
        credential = source.credential,
        deviceId = source.deviceId,
        url = buildEmbyApiUrl(source.baseUrl, "Items", itemId, "Lyrics"),
        method = RequestMethod.GET,
        body = null,
        operation = "Lyrics",
        logger = logger,
        notFoundAsNull = true,
    )?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return parseEmbyLyricsPayload(payload, EMBY_LYRICS_SOURCE_ID)
}

internal fun parseEmbyLyricsPayload(
    payload: String,
    sourceId: String = EMBY_LYRICS_SOURCE_ID,
): LyricsDocument? {
    val parsed = runCatching { embyJson.parseToJsonElement(payload) }.getOrNull()
        ?: return parseCachedLyrics(sourceId, payload)
    return when (parsed) {
        is JsonObject -> parseEmbyLyricsObject(parsed, sourceId, payload)
        is JsonArray -> parseEmbyLyricsArray(parsed, sourceId, payload)
        is JsonPrimitive -> parsed.contentOrNull?.let { parseCachedLyrics(sourceId, it) }
    } ?: parseCachedLyrics(sourceId, payload)
}

private suspend fun requestEmbyItemsPage(
    baseUrl: String,
    credential: EmbyCredential,
    deviceId: String,
    startIndex: Int,
    httpClient: LyricsHttpClient,
    logger: DiagnosticLogger,
): EmbyItemsPage {
    val url = URLBuilder(baseUrl).apply {
        appendPathSegments("Users", credential.userId, "Items")
        parameters.append("Recursive", "true")
        parameters.append("MediaTypes", "Audio")
        parameters.append("IncludeItemTypes", "Audio")
        parameters.append("Fields", "MediaSources,Path,PrimaryImageAspectRatio,Genres,SortName")
        parameters.append("EnableImages", "true")
        parameters.append("EnableImageTypes", "Primary")
        parameters.append("StartIndex", startIndex.toString())
        parameters.append("Limit", EMBY_PAGE_SIZE.toString())
    }.buildString()
    val response = requestEmbyJson(
        httpClient = httpClient,
        credential = credential,
        deviceId = deviceId,
        url = url,
        method = RequestMethod.GET,
        body = null,
        operation = "Items",
        logger = logger,
    )
    val items = response["Items"].asObjectList().map { it.toEmbyAudioItem() }
    return EmbyItemsPage(
        items = items,
        totalRecordCount = response.int("TotalRecordCount") ?: items.size,
    )
}

private suspend fun requestEmbyPagedItems(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    operation: String,
    parameters: Map<String, String>,
    logger: DiagnosticLogger,
    pageSize: Int = EMBY_PAGE_SIZE,
    maxItems: Int = Int.MAX_VALUE,
): List<JsonObject> {
    val items = mutableListOf<JsonObject>()
    var startIndex = 0
    while (items.size < maxItems) {
        val payload = requestEmbyJson(
            httpClient = httpClient,
            source = source,
            method = RequestMethod.GET,
            pathSegments = arrayOf("Users", source.credential.userId, "Items"),
            parameters = parameters + mapOf(
                "StartIndex" to startIndex.toString(),
                "Limit" to pageSize.toString(),
            ),
            operation = operation,
            logger = logger,
        )
        val pageItems = payload["Items"].asObjectList()
        if (pageItems.isEmpty()) break
        items += pageItems.take(maxItems - items.size)
        val totalRecordCount = payload.int("TotalRecordCount") ?: items.size
        startIndex += pageItems.size
        if (startIndex >= totalRecordCount) break
    }
    return items
}

private suspend fun requestEmbyPagedPlaylistItems(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    playlistId: String,
    logger: DiagnosticLogger,
): List<JsonObject> {
    val items = mutableListOf<JsonObject>()
    var startIndex = 0
    while (true) {
        val payload = requestEmbyJson(
            httpClient = httpClient,
            source = source,
            method = RequestMethod.GET,
            pathSegments = arrayOf("Playlists", playlistId, "Items"),
            parameters = mapOf(
                "UserId" to source.credential.userId,
                "Fields" to "UserData",
                "EnableUserData" to "true",
                "StartIndex" to startIndex.toString(),
                "Limit" to EMBY_PAGE_SIZE.toString(),
            ),
            operation = "PlaylistItems",
            logger = logger,
        )
        val pageItems = payload["Items"].asObjectList()
        if (pageItems.isEmpty()) break
        items += pageItems
        val totalRecordCount = payload.int("TotalRecordCount") ?: items.size
        startIndex += pageItems.size
        if (startIndex >= totalRecordCount) break
    }
    return items
}

private suspend fun requestEmbyJson(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    method: RequestMethod,
    pathSegments: Array<String>,
    parameters: Map<String, String> = emptyMap(),
    body: String? = null,
    operation: String,
    logger: DiagnosticLogger,
): JsonObject {
    val payload = requestEmbyText(
        httpClient = httpClient,
        credential = source.credential,
        deviceId = source.deviceId,
        url = buildEmbyApiUrl(source.baseUrl, *pathSegments, parameters = parameters),
        method = method,
        body = body,
        operation = operation,
        logger = logger,
    )
    return parseEmbyObject(payload, operation)
}

private suspend fun requestEmbyUnit(
    httpClient: LyricsHttpClient,
    source: EmbyResolvedSource,
    method: RequestMethod,
    pathSegments: Array<String>,
    parameters: Map<String, String> = emptyMap(),
    body: String? = null,
    operation: String,
    logger: DiagnosticLogger,
) {
    requestEmbyText(
        httpClient = httpClient,
        credential = source.credential,
        deviceId = source.deviceId,
        url = buildEmbyApiUrl(source.baseUrl, *pathSegments, parameters = parameters),
        method = method,
        body = body,
        operation = operation,
        logger = logger,
    )
}

private suspend fun requestEmbyJson(
    httpClient: LyricsHttpClient,
    credential: EmbyCredential,
    deviceId: String,
    url: String,
    method: RequestMethod,
    body: String?,
    operation: String,
    logger: DiagnosticLogger,
): JsonObject {
    return parseEmbyObject(
        requestEmbyText(
            httpClient = httpClient,
            credential = credential,
            deviceId = deviceId,
            url = url,
            method = method,
            body = body,
            operation = operation,
            logger = logger,
        ),
        operation,
    )
}

private suspend fun requestEmbyText(
    httpClient: LyricsHttpClient,
    credential: EmbyCredential,
    deviceId: String,
    url: String,
    method: RequestMethod,
    body: String?,
    operation: String,
    logger: DiagnosticLogger,
): String {
    return requestEmbyTextOrNull(
        httpClient = httpClient,
        credential = credential,
        deviceId = deviceId,
        url = url,
        method = method,
        body = body,
        operation = operation,
        logger = logger,
        notFoundAsNull = false,
    ) ?: error("Emby $operation 返回为空。")
}

private suspend fun requestEmbyTextOrNull(
    httpClient: LyricsHttpClient,
    credential: EmbyCredential,
    deviceId: String,
    url: String,
    method: RequestMethod,
    body: String?,
    operation: String,
    logger: DiagnosticLogger,
    notFoundAsNull: Boolean,
): String? {
    val request = LyricsRequest(
        method = method,
        url = url,
        headers = buildMap {
            put("X-Emby-Token", credential.accessToken)
            put("X-Emby-Authorization", embyAuthorizationHeader(deviceId = deviceId, credential = credential))
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        },
        body = body,
    )
    logEmbyRequest(logger, operation, request.url)
    val response = httpClient.request(request).getOrElse { throwable ->
        throw IllegalStateException("Emby $operation 请求失败: ${throwable.message.orEmpty()}", throwable)
    }
    if (response.statusCode == 404 && notFoundAsNull) return null
    require(response.statusCode in 200..299) { "Emby $operation 失败，HTTP ${response.statusCode}" }
    return response.body
}

private fun buildEmbyApiUrl(
    baseUrl: String,
    vararg pathSegments: String,
    parameters: Map<String, String> = emptyMap(),
): String {
    return URLBuilder(baseUrl).apply {
        appendPathSegments(*pathSegments)
        parameters.forEach { (key, value) ->
            if (value.isNotBlank()) {
                this.parameters.append(key, value)
            }
        }
    }.buildString()
}

private fun embyAuthorizationHeader(deviceId: String, credential: EmbyCredential? = null): String {
    return buildString {
        append("Emby Client=\"$EMBY_CLIENT_NAME\", Device=\"$EMBY_DEVICE_NAME\", DeviceId=\"$deviceId\", Version=\"$EMBY_VERSION\"")
        credential?.let {
            append(", UserId=\"")
            append(it.userId)
            append("\", Token=\"")
            append(it.accessToken)
            append('"')
        }
    }
}

private fun generateEmbyDeviceId(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    return "lynmusic-" + bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun logEmbyRequest(logger: DiagnosticLogger, operation: String, url: String) {
    if (logger === NoopDiagnosticLogger) return
    logger.log(
        level = DiagnosticLogLevel.INFO,
        tag = "Emby",
        message = "request operation=$operation\nurl: ${redactEmbyUrlForLog(url)}",
    )
}

private fun redactEmbyUrlForLog(url: String): String {
    return url.replace(Regex("([?&]api_key=)[^&]*"), "$1<redacted>")
}

private data class EmbyItemsPage(
    val items: List<EmbyAudioItem>,
    val totalRecordCount: Int,
)

private data class EmbyAudioItem(
    val itemId: String,
    val title: String,
    val artistName: String?,
    val albumTitle: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val sizeBytes: Long,
    val suffix: String?,
    val fileName: String?,
    val path: String?,
    val coverItemId: String?,
    val bitRate: Int?,
    val channelCount: Int?,
    val samplingRate: Int?,
    val bitDepth: Int?,
) {
    fun suffix(): String? = suffix?.trim()?.takeIf { it.isNotBlank() }
        ?: fileName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
        ?: path?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
}

private fun JsonObject.toEmbyAudioItem(): EmbyAudioItem {
    val itemId = string("Id").orEmpty()
    val title = string("Name").orEmpty().ifBlank { "未知曲目" }
    val mediaSource = this["MediaSources"].asObjectList().firstOrNull()
    val artistName = stringArray("Artists").firstOrNull()
        ?: string("AlbumArtist")
        ?: this["ArtistItems"].asObjectList().firstNotNullOfOrNull { it.string("Name") }
    val albumTitle = string("Album")
    val suffix = firstNonBlank(
        string("Container"),
        mediaSource?.string("Container"),
        string("FileName")?.substringAfterLast('.', missingDelimiterValue = ""),
        string("Path")?.substringAfterLast('.', missingDelimiterValue = ""),
    )
    return EmbyAudioItem(
        itemId = itemId,
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = ((long("RunTimeTicks") ?: mediaSource?.long("RunTimeTicks") ?: 0L) / 10_000L).coerceAtLeast(0L),
        trackNumber = int("IndexNumber"),
        discNumber = int("ParentIndexNumber"),
        sizeBytes = long("Size") ?: mediaSource?.long("Size") ?: 0L,
        suffix = suffix,
        fileName = string("FileName"),
        path = string("Path"),
        coverItemId = resolveEmbyCoverItemId(this, itemId),
        bitRate = int("Bitrate") ?: mediaSource?.int("Bitrate"),
        channelCount = firstAudioMediaStream(mediaSource)?.int("Channels"),
        samplingRate = firstAudioMediaStream(mediaSource)?.int("SampleRate"),
        bitDepth = firstAudioMediaStream(mediaSource)?.int("BitDepth"),
    )
}

private fun firstAudioMediaStream(mediaSource: JsonObject?): JsonObject? {
    return mediaSource?.get("MediaStreams")
        .asObjectList()
        .firstOrNull { it.string("Type").orEmpty().equals("Audio", ignoreCase = true) }
}

private fun resolveEmbyCoverItemId(item: JsonObject, itemId: String): String? {
    item.string("PrimaryImageItemId")?.takeIf { it.isNotBlank() }?.let { return it }
    if (item.string("PrimaryImageTag").isNullOrBlank().not() || item["ImageTags"].asObjectOrNull()?.string("Primary").isNullOrBlank().not()) {
        return itemId
    }
    val albumId = item.string("AlbumId")
    if (!albumId.isNullOrBlank() && !item.string("AlbumPrimaryImageTag").isNullOrBlank()) {
        return albumId
    }
    return null
}

private fun EmbyAudioItem.relativePath(): String {
    val fileName = buildString {
        append(normalizeEmbyPathSegment(title.ifBlank { "未知曲目" }))
        suffix()?.let {
            append('.')
            append(it.lowercase())
        }
    }
    return listOf(
        normalizeEmbyPathSegment(artistName.orEmpty().ifBlank { "未知艺人" }),
        normalizeEmbyPathSegment(albumTitle.orEmpty().ifBlank { "未知专辑" }),
        fileName,
    ).joinToString("/")
}

private fun EmbyAudioItem.toImportedTrackCandidate(sourceId: String): ImportedTrackCandidate {
    return ImportedTrackCandidate(
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        mediaLocator = buildEmbySongLocator(sourceId, itemId),
        relativePath = relativePath(),
        artworkLocator = coverItemId?.let { buildEmbyCoverLocator(sourceId, it) },
        sizeBytes = sizeBytes,
        bitRate = bitRate,
        channelCount = channelCount,
        samplingRate = samplingRate,
        bitDepth = bitDepth,
    )
}

private fun normalizeEmbyPathSegment(value: String): String {
    return value.trim()
        .replace('/', '／')
        .replace('\\', '／')
        .ifBlank { "未知" }
}

private fun parseEmbyObject(payload: String, context: String): JsonObject {
    return embyJson.parseToJsonElement(payload) as? JsonObject
        ?: error("Emby $context 返回不是 JSON 对象。")
}

private fun parseEmbyLyricsObject(
    payload: JsonObject,
    sourceId: String,
    rawPayload: String,
): LyricsDocument? {
    val structured = payload["Lyrics"] ?: payload["lyrics"] ?: payload["Lines"] ?: payload["lines"]
    when (structured) {
        is JsonArray -> parseEmbyLyricsArray(structured, sourceId, rawPayload)?.let { return it }
        is JsonPrimitive -> structured.contentOrNull?.let { return parseCachedLyrics(sourceId, it) }
        is JsonObject -> {
            structured.string("Value")
                ?.takeIf { it.isNotBlank() }
                ?.let { return parseCachedLyrics(sourceId, it) }
        }
        null -> Unit
    }
    firstNonBlank(
        payload.string("Value"),
        payload.string("Text"),
        payload.string("Lyric"),
        payload.string("Lyrics"),
        payload.string("lyrics"),
    )?.let { return parseCachedLyrics(sourceId, it) }
    return null
}

private fun parseEmbyLyricsArray(
    payload: JsonArray,
    sourceId: String,
    rawPayload: String,
): LyricsDocument? {
    val lines = payload.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { LyricsLine(timestampMs = null, text = it) }

            is JsonObject -> {
                val text = firstNonBlank(
                    element.string("Text"),
                    element.string("Line"),
                    element.string("Value"),
                    element.string("text"),
                ) ?: return@mapNotNull null
                val timestampMs = firstNonNull(
                    element.long("Start"),
                    element.long("StartTicks"),
                    element.long("StartPositionTicks"),
                    element.long("start"),
                )?.let(::embyTicksToMillis)
                    ?: firstNonNull(
                        element.long("StartMs"),
                        element.long("TimeMs"),
                        element.long("TimestampMs"),
                    )
                LyricsLine(timestampMs = timestampMs, text = text)
            }

            else -> null
        }
    }
    if (lines.isEmpty()) return null
    return LyricsDocument(
        lines = lines.sortedBy { it.timestampMs ?: Long.MAX_VALUE },
        sourceId = sourceId,
        rawPayload = rawPayload,
    )
}

private fun embyTicksToMillis(value: Long): Long = value / 10_000L

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun <T : Any> firstNonNull(vararg values: T?): T? {
    return values.firstOrNull { it != null }
}

private fun parseEmbyTimestampMillis(value: String): Long? {
    return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asObjectList(): List<JsonObject> {
    return when (val element = this) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

private fun JsonObject.stringArray(key: String): List<String> {
    return when (val element = this[key]) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }
}
