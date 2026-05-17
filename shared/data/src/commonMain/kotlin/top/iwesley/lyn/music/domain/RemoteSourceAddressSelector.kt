package top.iwesley.lyn.music.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.NetworkConnectionType
import top.iwesley.lyn.music.core.model.NetworkConnectionTypeProvider
import top.iwesley.lyn.music.core.model.RemotePlaybackUrlCandidate
import top.iwesley.lyn.music.core.model.WifiNetworkConnectionTypeProvider

enum class RemoteSourceAddressKind {
    LAN,
    WAN,
}

data class RemoteSourceBaseUrl(
    val kind: RemoteSourceAddressKind,
    val value: String,
)

data class RemoteSourceResolvedUrl(
    val sourceId: String,
    val kind: RemoteSourceAddressKind,
    val value: String,
)

class RemoteSourceAddressSelector(
    private val networkConnectionTypeProvider: NetworkConnectionTypeProvider = WifiNetworkConnectionTypeProvider,
    private val ttlMillis: Long = DEFAULT_REMOTE_SOURCE_ADDRESS_CACHE_TTL_MILLIS,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val successfulAddressCache = MutableStateFlow<Map<String, SuccessfulAddress>>(emptyMap())

    fun orderedBaseUrls(
        sourceId: String,
        sourceType: ImportSourceType,
        lanBaseUrl: String?,
        wanBaseUrl: String?,
        normalizeBaseUrl: (String) -> String,
    ): List<RemoteSourceBaseUrl> {
        val addresses = normalizeAddresses(
            sourceType = sourceType,
            lanBaseUrl = lanBaseUrl,
            wanBaseUrl = wanBaseUrl,
            normalizeBaseUrl = normalizeBaseUrl,
        )
        if (addresses.size <= 1) return addresses
        val networkState = networkConnectionTypeProvider.networkConnectionState.value
        val now = nowMillis()
        successfulAddressCache.value[sourceId]
            ?.takeIf { it.networkVersion == networkState.version && now - it.recordedAtMillis <= ttlMillis }
            ?.let { cached ->
                addresses.firstOrNull { it.kind == cached.kind }?.let { preferred ->
                    return listOf(preferred) + addresses.filterNot { it.kind == preferred.kind }
                }
            }
        return addresses.orderedForNetwork(networkState.type)
    }

    suspend fun <T> withAddressFallback(
        sourceId: String,
        sourceType: ImportSourceType,
        lanBaseUrl: String?,
        wanBaseUrl: String?,
        normalizeBaseUrl: (String) -> String,
        block: suspend (RemoteSourceBaseUrl) -> T,
    ): T {
        val candidates = orderedBaseUrls(
            sourceId = sourceId,
            sourceType = sourceType,
            lanBaseUrl = lanBaseUrl,
            wanBaseUrl = wanBaseUrl,
            normalizeBaseUrl = normalizeBaseUrl,
        )
        var lastFailure: Throwable? = null
        candidates.forEachIndexed { index, candidate ->
            try {
                val result = block(candidate)
                markSuccess(sourceId, candidate.kind)
                return result
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastFailure = throwable
                val hasFallback = index < candidates.lastIndex
                if (!hasFallback || !isRemoteSourceAddressFallbackAllowed(throwable)) {
                    throw throwable
                }
            }
        }
        throw lastFailure ?: IllegalStateException("${sourceType.displayName()} 来源缺少可用服务器地址。")
    }

    fun markSuccess(sourceId: String, kind: RemoteSourceAddressKind) {
        val networkState = networkConnectionTypeProvider.networkConnectionState.value
        val successfulAddress = SuccessfulAddress(
            kind = kind,
            networkVersion = networkState.version,
            recordedAtMillis = nowMillis(),
        )
        successfulAddressCache.update { cache -> cache + (sourceId to successfulAddress) }
    }

    fun invalidate(sourceId: String) {
        successfulAddressCache.update { cache -> cache - sourceId }
    }

    fun clear() {
        successfulAddressCache.update { emptyMap() }
    }
}

fun normalizeRemoteSourceBaseUrls(
    sourceType: ImportSourceType,
    lanBaseUrl: String?,
    wanBaseUrl: String?,
    normalizeBaseUrl: (String) -> String,
): Pair<String, String?> {
    val addresses = normalizeAddresses(sourceType, lanBaseUrl, wanBaseUrl, normalizeBaseUrl)
    val lan = addresses.firstOrNull { it.kind == RemoteSourceAddressKind.LAN }?.value.orEmpty()
    val wan = addresses.firstOrNull { it.kind == RemoteSourceAddressKind.WAN }?.value
    return lan to wan
}

fun isRemoteSourceAddressFallbackAllowed(throwable: Throwable): Boolean {
    val chain = throwable.messageChain()
    val statusCodes = HTTP_STATUS_REGEX.findAll(chain)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()
    if (statusCodes.any { it == 401 || it == 403 }) return false
    if (statusCodes.isNotEmpty()) return statusCodes.any { it == 408 || it in 500..599 }
    val lowered = chain.lowercase()
    if (
        lowered.contains("地址无效") ||
        lowered.contains("不能包含 query") ||
        lowered.contains("只支持 http") ||
        lowered.contains("缺少主机名") ||
        lowered.contains("内嵌用户名") ||
        lowered.contains("缺少有效凭据") ||
        lowered.contains("缺少有效密码") ||
        lowered.contains("token 为空") ||
        lowered.contains("用户 id 为空")
    ) {
        return false
    }
    return lowered.contains("请求失败") ||
        lowered.contains("timeout") ||
        lowered.contains("timed out") ||
        lowered.contains("unknownhost") ||
        lowered.contains("unknown host") ||
        lowered.contains("nsurlerrordomain code=-1001") ||
        lowered.contains("nsurlerrordomain code=-1003") ||
        lowered.contains("nsurlerrordomain code=-1004") ||
        lowered.contains("nsurlerrordomain code=-1005") ||
        lowered.contains("nsurlerrordomain code=-1009") ||
        lowered.contains("nsurlerrordomain code=-1200") ||
        lowered.contains("nsurlerrordomain code=-1202") ||
        lowered.contains("resolve") ||
        lowered.contains("connect") ||
        lowered.contains("connection") ||
        lowered.contains("network") ||
        lowered.contains("tls") ||
        lowered.contains("ssl")
}

suspend fun <T> readRemotePlaybackUrlCandidateWithFallback(
    candidates: List<RemotePlaybackUrlCandidate>,
    isRemoteUrl: (String) -> Boolean = { value ->
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    },
    read: suspend (RemotePlaybackUrlCandidate) -> T,
    isValidPayload: (T) -> Boolean = { true },
): Pair<RemotePlaybackUrlCandidate, T>? {
    val remoteCandidates = candidates.filter { candidate -> isRemoteUrl(candidate.value) }
    if (remoteCandidates.isEmpty()) return null
    var lastFailure: Throwable? = null
    remoteCandidates.forEachIndexed { index, candidate ->
        try {
            val payload = read(candidate)
            return if (isValidPayload(payload)) candidate to payload else null
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            lastFailure = throwable
            val hasFallback = index < remoteCandidates.lastIndex
            if (!hasFallback || !isRemoteSourceAddressFallbackAllowed(throwable)) {
                throw throwable
            }
        }
    }
    throw lastFailure ?: IllegalStateException("远程来源缺少可用地址。")
}

private fun normalizeAddresses(
    sourceType: ImportSourceType,
    lanBaseUrl: String?,
    wanBaseUrl: String?,
    normalizeBaseUrl: (String) -> String,
): List<RemoteSourceBaseUrl> {
    val lan = lanBaseUrl.orEmpty().trim()
        .takeIf { it.isNotBlank() }
        ?.let(normalizeBaseUrl)
    val wan = wanBaseUrl.orEmpty().trim()
        .takeIf { it.isNotBlank() }
        ?.let(normalizeBaseUrl)
    require(lan != null || wan != null) { "请至少填写一个${sourceType.displayName()}服务器地址。" }
    return buildList {
        lan?.let { add(RemoteSourceBaseUrl(RemoteSourceAddressKind.LAN, it)) }
        wan?.let { add(RemoteSourceBaseUrl(RemoteSourceAddressKind.WAN, it)) }
    }
}

private fun List<RemoteSourceBaseUrl>.orderedForNetwork(networkType: NetworkConnectionType): List<RemoteSourceBaseUrl> {
    val preferredKind = when (networkType) {
        NetworkConnectionType.WIFI -> RemoteSourceAddressKind.LAN
        NetworkConnectionType.MOBILE -> RemoteSourceAddressKind.WAN
    }
    val preferred = firstOrNull { it.kind == preferredKind }
    return if (preferred == null) this else listOf(preferred) + filterNot { it.kind == preferredKind }
}

private fun Throwable.messageChain(): String {
    return generateSequence(this) { it.cause }
        .map { throwable ->
            val name = throwable::class.simpleName ?: throwable::class.qualifiedName ?: "Throwable"
            val message = throwable.message?.takeIf { it.isNotBlank() }
            if (message == null) name else "$name: $message"
        }
        .distinct()
        .joinToString(" -> ")
}

private fun ImportSourceType.displayName(): String {
    return when (this) {
        ImportSourceType.NAVIDROME -> "Navidrome"
        ImportSourceType.SUBSONIC -> "Subsonic"
        ImportSourceType.EMBY -> "Emby"
        else -> "远程"
    }
}

private data class SuccessfulAddress(
    val kind: RemoteSourceAddressKind,
    val networkVersion: Long,
    val recordedAtMillis: Long,
)

private val HTTP_STATUS_REGEX =
    Regex("\\b(?:HTTP\\s+|status\\s*[=:]?\\s*|response\\s*code\\s*[=:]?\\s*|code\\s*[=:]?\\s*)(\\d{3})\\b", RegexOption.IGNORE_CASE)
private const val DEFAULT_REMOTE_SOURCE_ADDRESS_CACHE_TTL_MILLIS = 10 * 60 * 1_000L
