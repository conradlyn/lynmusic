package top.iwesley.lyn.music.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.SystemAudioFocusChange
import top.iwesley.lyn.music.core.model.SystemAudioFocusCommand
import top.iwesley.lyn.music.core.model.SystemAudioFocusState
import top.iwesley.lyn.music.core.model.SystemPlaybackControlCallbacks
import top.iwesley.lyn.music.core.model.SystemPlaybackControlsPlatformService
import top.iwesley.lyn.music.core.model.resolveSystemAudioFocusChange
import top.iwesley.lyn.music.core.model.shouldKeepAudioFocusWhilePausedForResume

internal fun createAndroidAudioFocusPlaybackControlsPlatformService(
    context: Context,
): SystemPlaybackControlsPlatformService {
    return AndroidAudioFocusPlaybackControlsPlatformService(context.applicationContext)
}

private class AndroidAudioFocusPlaybackControlsPlatformService(
    private val context: Context,
) : SystemPlaybackControlsPlatformService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var callbacks = SystemPlaybackControlCallbacks()
    private var latestSnapshot = PlaybackSnapshot()
    private var isNoisyReceiverRegistered = false
    private var hasAudioFocus = false
    private var audioFocusState = SystemAudioFocusState()
    private var audioFocusRequest: AudioFocusRequest? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && latestSnapshot.isPlaying) {
                serviceScope.launch { callbacks.pause() }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val change = focusChange.toSystemAudioFocusChange() ?: return@OnAudioFocusChangeListener
        val result = resolveSystemAudioFocusChange(
            state = audioFocusState,
            change = change,
            isPlaying = latestSnapshot.isPlaying,
            hasCurrentTrack = latestSnapshot.currentTrack != null,
        )
        audioFocusState = result.state
        if (change == SystemAudioFocusChange.Gain) {
            hasAudioFocus = true
        }
        when (result.command) {
            SystemAudioFocusCommand.Play -> serviceScope.launch { callbacks.play() }
            SystemAudioFocusCommand.Pause -> serviceScope.launch { callbacks.pause() }
            SystemAudioFocusCommand.None -> Unit
        }
    }

    override fun bind(callbacks: SystemPlaybackControlCallbacks) {
        this.callbacks = callbacks
    }

    override suspend fun updateSnapshot(snapshot: PlaybackSnapshot) {
        latestSnapshot = snapshot
        updateAudioFocus(snapshot)
        updateNoisyReceiver(snapshot)
    }

    override suspend fun close() {
        callbacks = SystemPlaybackControlCallbacks()
        latestSnapshot = PlaybackSnapshot()
        updateNoisyReceiver(latestSnapshot)
        audioFocusState = SystemAudioFocusState()
        abandonAudioFocus()
        serviceScope.cancel()
    }

    private fun updateAudioFocus(snapshot: PlaybackSnapshot) {
        if (snapshot.isPlaying) {
            if (!requestAudioFocus()) {
                serviceScope.launch { callbacks.pause() }
            }
        } else if (shouldKeepAudioFocusWhilePausedForResume(audioFocusState)) {
            return
        } else {
            audioFocusState = SystemAudioFocusState()
            abandonAudioFocus()
        }
    }

    private fun updateNoisyReceiver(snapshot: PlaybackSnapshot) {
        if (snapshot.isPlaying && !isNoisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isNoisyReceiverRegistered = true
        } else if (!snapshot.isPlaying && isNoisyReceiverRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            isNoisyReceiverRegistered = false
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setWillPauseWhenDucked(false)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun Int.toSystemAudioFocusChange(): SystemAudioFocusChange? {
        return when (this) {
            AudioManager.AUDIOFOCUS_GAIN -> SystemAudioFocusChange.Gain
            AudioManager.AUDIOFOCUS_LOSS -> SystemAudioFocusChange.Loss
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> SystemAudioFocusChange.LossTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> SystemAudioFocusChange.LossTransientCanDuck
            else -> null
        }
    }
}
