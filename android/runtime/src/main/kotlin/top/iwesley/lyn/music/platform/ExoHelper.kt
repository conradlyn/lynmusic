package top.iwesley.lyn.music.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor as CoreFlacExtractor

@UnstableApi
class FfmpegFlacExtractorsFactory : ExtractorsFactory {
    private val defaultFactory = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> =
        forceCoreFlac(defaultFactory.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        forceCoreFlac(defaultFactory.createExtractors(uri, responseHeaders))

    private fun forceCoreFlac(defaults: Array<Extractor>): Array<Extractor> =
        arrayOf<Extractor>(CoreFlacExtractor()) +
            defaults.filterNot { it.javaClass.name == CoreFlacExtractor::class.java.name }
}

@UnstableApi
class LynAudioRenderersFactory(
    context: Context,
    private val preferFfmpeg: Boolean,
) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        if (preferFfmpeg) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }

        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
