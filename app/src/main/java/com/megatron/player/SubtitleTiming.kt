package com.megatron.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import java.security.MessageDigest
import java.util.Locale

/** Positive offset delays subtitles, independently of the PCM audio adjustment. */
class SubtitleTiming(context: Context, uri: Uri) {
    private val prefs = context.applicationContext.getSharedPreferences("subtitle_timing_v1", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    @Volatile var offsetMs: Int = prefs.getInt(key, 0).coerceIn(-10_000, 10_000)
        private set

    fun set(value: Int) {
        offsetMs = value.coerceIn(-10_000, 10_000)
        prefs.edit().apply { if (offsetMs == 0) remove(key) else putInt(key, offsetMs) }.apply()
    }
}

/** Uses the text renderer's actual scheduling clock, for embedded and external subtitles alike. */
@UnstableApi
internal fun subtitleTimingFactory(delegate: RenderersFactory, timing: SubtitleTiming): RenderersFactory =
    RenderersFactory { handler, video, audio, text, metadata ->
        delegate.createRenderers(handler, video, audio, text, metadata).map { renderer ->
            if (renderer.trackType == C.TRACK_TYPE_TEXT) SubtitleTimingRenderer(renderer) { timing.offsetMs } else renderer
        }.toTypedArray()
    }

@UnstableApi
internal class SubtitleTimingRenderer(delegate: Renderer, private val offsetMs: () -> Int) : ForwardingRenderer(delegate) {
    override fun enable(
        configuration: androidx.media3.exoplayer.RendererConfiguration,
        formats: Array<androidx.media3.common.Format>,
        stream: androidx.media3.exoplayer.source.SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
    ) = super.enable(configuration, formats, stream, subtitleClockPositionUs(positionUs, offsetMs()),
        joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        super.render(subtitleClockPositionUs(positionUs, offsetMs()), elapsedRealtimeUs)

    override fun resetPosition(positionUs: Long, streamReset: Boolean) =
        super.resetPosition(subtitleClockPositionUs(positionUs, offsetMs()), streamReset)

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(subtitleClockPositionUs(positionUs, offsetMs()), elapsedRealtimeUs)
}

internal fun subtitleClockPositionUs(positionUs: Long, offsetMs: Int): Long = positionUs - offsetMs.coerceIn(-10_000, 10_000) * 1000L

internal fun subtitleOffsetText(offsetMs: Int): String = when {
    offsetMs > 0 -> "字幕延后 ${String.format(Locale.ROOT, "%.1f", offsetMs / 1000f)} 秒"
    offsetMs < 0 -> "字幕提前 ${String.format(Locale.ROOT, "%.1f", -offsetMs / 1000f)} 秒"
    else -> "字幕无偏移"
}
