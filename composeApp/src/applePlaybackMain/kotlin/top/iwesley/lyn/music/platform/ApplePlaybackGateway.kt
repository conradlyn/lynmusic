package top.iwesley.lyn.music.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.iwesley.lyn.music.core.model.AppleMediaLocatorResolver
import top.iwesley.lyn.music.core.model.AppleResolvedMediaLocator
import top.iwesley.lyn.music.core.model.NavidromeAudioQualityPreferencesStore
import top.iwesley.lyn.music.core.model.NavidromeLocatorRuntime
import top.iwesley.lyn.music.core.model.NetworkConnectionTypeProvider
import top.iwesley.lyn.music.core.model.PlaybackGateway
import top.iwesley.lyn.music.core.model.PlaybackGatewayState
import top.iwesley.lyn.music.core.model.PlaybackLoadToken
import top.iwesley.lyn.music.core.model.RemotePlaybackUrlCandidate
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.UnsupportedNavidromeAudioQualityPreferencesStore
import top.iwesley.lyn.music.core.model.WifiNetworkConnectionTypeProvider
import top.iwesley.lyn.music.core.model.parseEmbySongLocator
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.core.model.resolveNavidromeAudioQualityForCurrentNetwork
import top.iwesley.lyn.music.domain.RemoteSourceAddressKind
import top.iwesley.lyn.music.domain.RemoteSourceAddressSelector
import top.iwesley.lyn.music.domain.isRemoteSourceAddressFallbackAllowed

private data class AppleRemotePlaybackFallback(
    val candidates: List<RemotePlaybackUrlCandidate>,
    val selectedIndex: Int,
    val playWhenReady: Boolean,
) {
    fun currentCandidate(): RemotePlaybackUrlCandidate? = candidates.getOrNull(selectedIndex)
}

internal class ApplePlaybackGateway(
    private val platformLabel: String,
    private val navidromeAudioQualityPreferencesStore: NavidromeAudioQualityPreferencesStore =
        UnsupportedNavidromeAudioQualityPreferencesStore,
    private val networkConnectionTypeProvider: NetworkConnectionTypeProvider = WifiNetworkConnectionTypeProvider,
    private val addressSelector: RemoteSourceAddressSelector? = null,
) : PlaybackGateway {
    private val player = AppleNativePlayer(platformLabel)
    private val mutableState = MutableStateFlow(PlaybackGatewayState(volume = 1f))
    private var currentRemotePlaybackFallback: AppleRemotePlaybackFallback? = null

    override val state: StateFlow<PlaybackGatewayState> = mutableState.asStateFlow()

    init {
        AppleAudioSessionCoordinator.configureForPlayback()
        player.onProgress = { publishState() }
        player.onCompleted = {
            mutableState.update {
                it.copy(
                    isPlaying = false,
                    positionMs = 0L,
                    canSeek = false,
                    completionCount = it.completionCount + 1,
                    errorMessage = null,
                )
            }
        }
        player.onFailed = onFailed@ { errorMessage ->
            if (tryApplyRemoteAddressFallback(errorMessage)) {
                return@onFailed
            }
            publishState(errorOverride = errorMessage ?: "$platformLabel 播放失败。")
        }
    }

    override suspend fun load(
        track: Track,
        playWhenReady: Boolean,
        startPositionMs: Long,
        loadToken: PlaybackLoadToken,
    ) {
        if (!loadToken.isCurrent()) {
            return
        }
        stopAndResetForTrackSwitch()
        val subsonicCompatible = parseSubsonicCompatibleSongLocator(track.mediaLocator)
        val embySong = parseEmbySongLocator(track.mediaLocator)
        val navidromeAudioQuality = subsonicCompatible?.let {
            resolveNavidromeAudioQualityForCurrentNetwork(
                preferencesStore = navidromeAudioQualityPreferencesStore,
                networkConnectionTypeProvider = networkConnectionTypeProvider,
            )
        }
        val remotePlaybackCandidates = when {
            subsonicCompatible != null -> NavidromeLocatorRuntime.resolveStreamUrlCandidates(
                locator = track.mediaLocator,
                audioQuality = requireNotNull(navidromeAudioQuality),
            )

            embySong != null -> NavidromeLocatorRuntime.resolveStreamUrlCandidates(track.mediaLocator)
            else -> null
        }?.takeIf { it.isNotEmpty() }
        val effectiveLocator = when {
            remotePlaybackCandidates != null -> remotePlaybackCandidates.first().value
            subsonicCompatible != null -> NavidromeLocatorRuntime.resolveStreamUrl(
                locator = track.mediaLocator,
                audioQuality = requireNotNull(navidromeAudioQuality),
            ) ?: track.mediaLocator

            embySong != null -> NavidromeLocatorRuntime.resolveStreamUrl(track.mediaLocator) ?: track.mediaLocator
            else -> track.mediaLocator
        }
        if (!loadToken.isCurrent()) {
            return
        }
        when (val resolved = AppleMediaLocatorResolver.resolve(effectiveLocator)) {
            is AppleResolvedMediaLocator.Unsupported -> {
                if (!loadToken.isCurrent()) {
                    return
                }
                mutableState.update {
                    it.copy(
                        canSeek = false,
                        errorMessage = resolved.message,
                    )
                }
            }

            else -> {
                if (!loadToken.isCurrent()) {
                    return
                }
                currentRemotePlaybackFallback = remotePlaybackCandidates?.let { candidates ->
                    AppleRemotePlaybackFallback(
                        candidates = candidates,
                        selectedIndex = 0,
                        playWhenReady = playWhenReady,
                    )
                }
                player.load(resolved)
                if (startPositionMs > 0L) {
                    player.seekTo(startPositionMs)
                }
                if (playWhenReady) {
                    player.play()
                } else {
                    player.pause()
                }
                mutableState.update {
                    it.copy(
                        isPlaying = playWhenReady,
                        positionMs = 0L,
                        durationMs = 0L,
                        canSeek = player.canSeek(),
                        currentNavidromeAudioQuality = navidromeAudioQuality,
                        errorMessage = null,
                    )
                }
                publishState()
            }
        }
    }

    override suspend fun play() {
        player.play()
        publishState()
    }

    override suspend fun pause() {
        player.pause()
        publishState()
    }

    override suspend fun seekTo(positionMs: Long) {
        if (!player.canSeek()) {
            mutableState.update { it.copy(canSeek = false) }
            return
        }
        player.seekTo(positionMs)
        mutableState.update {
            it.copy(
                positionMs = positionMs.coerceAtLeast(0L),
                canSeek = player.canSeek(),
                errorMessage = null,
            )
        }
        publishState()
    }

    override suspend fun setVolume(volume: Float) {
        player.setVolume(volume)
        publishState()
    }

    override suspend fun release() {
        currentRemotePlaybackFallback = null
        player.release()
        AppleAudioSessionCoordinator.deactivate()
    }

    private fun stopAndResetForTrackSwitch() {
        player.stopAndClear()
        currentRemotePlaybackFallback = null
        mutableState.update {
            it.resetForTrackSwitch(volumeOverride = player.volume())
        }
    }

    private fun publishState(errorOverride: String? = null) {
        if (player.isPlaying()) {
            markCurrentRemotePlaybackSuccess()
        }
        mutableState.update {
            it.copy(
                isPlaying = player.isPlaying(),
                positionMs = player.positionMs().coerceAtLeast(0L),
                durationMs = player.durationMs()?.takeIf { value -> value > 0L } ?: it.durationMs,
                canSeek = player.canSeek(),
                volume = player.volume().coerceIn(0f, 1f),
                errorMessage = errorOverride ?: player.errorMessage(),
            )
        }
    }

    private fun tryApplyRemoteAddressFallback(errorMessage: String?): Boolean {
        val fallback = currentRemotePlaybackFallback ?: return false
        if (!isAppleRemotePlaybackFallbackAllowed(errorMessage)) return false
        val nextIndex = fallback.selectedIndex + 1
        val nextCandidate = fallback.candidates.getOrNull(nextIndex) ?: return false
        val resolved = AppleMediaLocatorResolver.resolve(nextCandidate.value)
        if (resolved is AppleResolvedMediaLocator.Unsupported) return false
        val retryPositionMs = player.positionMs().coerceAtLeast(0L)
        val retryPlayWhenReady = player.isPlaying() || fallback.playWhenReady
        currentRemotePlaybackFallback = fallback.copy(
            selectedIndex = nextIndex,
            playWhenReady = retryPlayWhenReady,
        )
        player.load(resolved)
        if (retryPositionMs > 0L) {
            player.seekTo(retryPositionMs)
        }
        if (retryPlayWhenReady) {
            player.play()
        } else {
            player.pause()
        }
        mutableState.update {
            it.copy(
                isPlaying = retryPlayWhenReady,
                positionMs = retryPositionMs,
                canSeek = player.canSeek(),
                errorMessage = null,
            )
        }
        return true
    }

    private fun markCurrentRemotePlaybackSuccess() {
        val candidate = currentRemotePlaybackFallback?.currentCandidate() ?: return
        val kind = runCatching { RemoteSourceAddressKind.valueOf(candidate.addressKind) }.getOrNull() ?: return
        candidate.sourceId.takeIf { it.isNotBlank() }?.let { sourceId ->
            addressSelector?.markSuccess(sourceId, kind)
        }
    }

    private fun isAppleRemotePlaybackFallbackAllowed(errorMessage: String?): Boolean =
        isRemoteSourceAddressFallbackAllowed(IllegalStateException(errorMessage.orEmpty()))
}
