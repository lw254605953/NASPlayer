package com.megatron.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.megatron.player.plex.PlexUris
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Runtime state for the audio-to-video offset. Positive values delay audio. */
@UnstableApi
class AudioSyncController(context: Context, sourceUri: Uri) {
    companion object {
        const val MIN_OFFSET_MS = -2_000
        const val MAX_OFFSET_MS = 2_000
        const val STEP_MS = 50
    }

    private val preferences = context.applicationContext
        .getSharedPreferences("audio_sync_v1", Context.MODE_PRIVATE)
    private val sourceKey = stableSourceKey(sourceUri)
    private var currentTrackKey: String? = null
    private var currentLabel = "当前音轨"
    @Volatile private var remembered = false
    @Volatile private var offset = 0

    val audioOffsetMs: Int get() = offset
    val audioSyncRemembered: Boolean get() = remembered

    fun updateAudioFormat(format: Format?): Boolean {
        val nextKey = format?.let(::trackKey)
        if (nextKey == currentTrackKey) {
            if (format != null) currentLabel = formatLabel(format)
            return false
        }
        currentTrackKey = nextKey
        currentLabel = format?.let(::formatLabel) ?: "当前音轨"
        val saved = nextKey?.let { readSaved(it) }
        remembered = saved != null
        offset = saved?.let(AudioSyncMath::clamp) ?: 0
        return true
    }

    fun setAudioOffsetMs(value: Int) {
        offset = AudioSyncMath.clamp(value)
        if (remembered) saveCurrent()
    }

    fun setAudioSyncRemembered(remember: Boolean) {
        remembered = remember
        if (remember) saveCurrent() else currentTrackKey?.let(::removeSaved)
    }

    fun audioSyncTrackLabel(): String = currentLabel

    fun shiftedPresentationTimeUs(presentationTimeUs: Long): Long {
        return AudioSyncMath.shiftPresentationTimeUs(presentationTimeUs, offset)
    }

    private fun saveCurrent() {
        val key = currentTrackKey ?: return
        preferences.edit().putInt(prefKey(key), offset).apply()
    }

    private fun readSaved(trackKey: String): Int? {
        val key = prefKey(trackKey)
        return if (preferences.contains(key)) preferences.getInt(key, 0) else null
    }

    private fun removeSaved(trackKey: String) {
        preferences.edit().remove(prefKey(trackKey)).apply()
    }

    private fun prefKey(trackKey: String): String = "offset_${digest(sourceKey + "|" + trackKey)}"

    private fun trackKey(format: Format): String = listOf(
        format.id.orEmpty(), format.sampleMimeType.orEmpty(), format.codecs.orEmpty(),
        format.language.orEmpty(), format.channelCount, format.sampleRate,
    ).joinToString("|")

    private fun formatLabel(format: Format): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
        val codec = format.codecs?.takeIf { it.isNotBlank() } ?: format.sampleMimeType.orEmpty()
        return listOfNotNull(language, codec.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "当前音轨" }
    }

    private fun stableSourceKey(uri: Uri): String = PlexUris.parse(uri)?.let {
        "plex:${it.serverId}:${it.ratingKey}:${it.partKey}"
    } ?: buildString {
        // Deliberately omit query/user-info so credentials never become part of the key.
        append(uri.scheme.orEmpty()).append("://")
        append(uri.host.orEmpty()).append(':').append(uri.port)
        append(uri.path.orEmpty())
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal object AudioSyncMath {
    fun clamp(value: Int): Int = value.coerceIn(AudioSyncController.MIN_OFFSET_MS, AudioSyncController.MAX_OFFSET_MS)

    fun shiftPresentationTimeUs(presentationTimeUs: Long, offsetMs: Int): Long {
        val shiftUs = offsetMs.toLong() * 1_000L
        return if (shiftUs > 0 && presentationTimeUs > Long.MAX_VALUE - shiftUs) Long.MAX_VALUE
        else if (shiftUs < 0 && presentationTimeUs < Long.MIN_VALUE - shiftUs) Long.MIN_VALUE
        else presentationTimeUs + shiftUs
    }
}

internal data class AudioSyncPcmPlan(
    val offsetUs: Long,
    val delayBytes: Int,
    val dropBytes: Int,
) {
    companion object {
        fun create(offsetMs: Int, sampleRateHz: Int, frameSizeBytes: Int): AudioSyncPcmPlan {
            if (sampleRateHz <= 0 || frameSizeBytes <= 0) return AudioSyncPcmPlan(0L, 0, 0)
            val clamped = AudioSyncMath.clamp(offsetMs)
            val frames = abs(clamped).toLong() * sampleRateHz / 1_000L
            val bytes = (frames * frameSizeBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val offsetUs = frames * 1_000_000L / sampleRateHz * if (clamped < 0) -1 else 1
            return AudioSyncPcmPlan(offsetUs, if (clamped > 0) bytes else 0, if (clamped < 0) bytes else 0)
        }
    }
}

/** Stateful PCM framing used by the sink; kept separate so cross-buffer behavior is testable. */
internal class AudioSyncPcmTransform(
    private val plan: AudioSyncPcmPlan,
    private val frameSizeBytes: Int,
    private val sampleRateHz: Int,
    pcmEncoding: Int,
) {
    var pendingSilence: ByteBuffer? = if (plan.delayBytes > 0) {
        ByteBuffer.allocateDirect(plan.delayBytes).order(ByteOrder.LITTLE_ENDIAN).also { silence ->
            val value = if (pcmEncoding == C.ENCODING_PCM_8BIT) 0x80 else 0
            while (silence.hasRemaining()) silence.put(value.toByte())
            silence.flip()
        }
    } else null
        private set

    private var dropBytesRemaining = plan.dropBytes
    private var mappedInput: ByteBuffer? = null
    private var mappedTimestampUs = 0L

    /** Returns the stable timestamp for the current input buffer, or null if it was fully dropped. */
    fun mapInput(buffer: ByteBuffer, presentationTimeUs: Long): Long? {
        if (mappedInput !== buffer) {
            var skipped = 0
            if (dropBytesRemaining > 0) {
                skipped = minOf(dropBytesRemaining, buffer.remaining())
                buffer.position(buffer.position() + skipped)
                dropBytesRemaining -= skipped
            }
            if (!buffer.hasRemaining()) {
                mappedInput = null
                return null
            }
            mappedInput = buffer
            mappedTimestampUs = presentationTimeUs +
                skipped.toLong() * 1_000_000L / (frameSizeBytes.toLong() * sampleRateHz) + plan.offsetUs
        }
        return mappedTimestampUs
    }

    fun markHandled(buffer: ByteBuffer, handled: Boolean) {
        if (handled && mappedInput === buffer) mappedInput = null
    }

    fun markSilenceHandled() {
        pendingSilence = null
    }
}

/** AudioSink decorator applying the offset to every decoded/output buffer timestamp. */
@UnstableApi
private class AudioSyncSink(
    private val delegate: AudioSink,
    private val controller: AudioSyncController,
) : AudioSink {
    private var frameSizeBytes = 0
    private var sampleRateHz = 0
    private var pcmEncoding = C.ENCODING_INVALID
    private var configured = false
    private var streamInitialized = false
    private var transform: AudioSyncPcmTransform? = null

    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)
    override fun supportsFormat(format: Format): Boolean = format.sampleMimeType == "audio/raw" && delegate.supportsFormat(format)
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == "audio/raw") delegate.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        delegate.configure(format, specifiedBufferSize, outputChannels)
        delegate.setSkipSilenceEnabled(false)
        configured = format.sampleMimeType == "audio/raw" && format.sampleRate > 0 && format.channelCount > 0
        pcmEncoding = if (configured) format.pcmEncoding else C.ENCODING_INVALID
        frameSizeBytes = if (configured) pcmBytesPerSample(pcmEncoding) * format.channelCount else 0
        sampleRateHz = if (configured) format.sampleRate else 0
        resetTransformState()
    }
    override fun play() = delegate.play()
    override fun handleDiscontinuity() {
        delegate.handleDiscontinuity()
        resetTransformState()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!configured || frameSizeBytes <= 0 || sampleRateHz <= 0) {
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!streamInitialized) initializeTransform()

        // Delay: submit real PCM silence first. The caller's buffer remains untouched when the
        // sink back-pressures, so ExoPlayer can retry the same input safely.
        val pcmTransform = transform ?: return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pcmTransform.pendingSilence?.let { silence ->
            if (!delegate.handleBuffer(silence, presentationTimeUs, 0)) return false
            pcmTransform.markSilenceHandled()
        }

        val timestampUs = pcmTransform.mapInput(buffer, presentationTimeUs) ?: return true
        val handled = delegate.handleBuffer(buffer, timestampUs, encodedAccessUnitCount)
        pcmTransform.markHandled(buffer, handled)
        return handled
    }
    override fun playToEndOfStream() = delegate.playToEndOfStream()
    override fun isEnded(): Boolean = delegate.isEnded()
    override fun hasPendingData(): Boolean = delegate.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = delegate.setPlaybackParameters(playbackParameters)
    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()
    override fun setSkipSilenceEnabled(enabled: Boolean) = delegate.setSkipSilenceEnabled(false)
    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = delegate.setAudioAttributes(audioAttributes)
    override fun getAudioAttributes(): AudioAttributes? = delegate.getAudioAttributes()
    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)
    override fun setPreferredDevice(preferredDevice: android.media.AudioDeviceInfo?) = delegate.setPreferredDevice(preferredDevice)
    override fun setVirtualDeviceId(virtualDeviceId: Int) = delegate.setVirtualDeviceId(virtualDeviceId)
    override fun setOutputStreamOffsetUs(offsetUs: Long) = delegate.setOutputStreamOffsetUs(offsetUs)
    override fun getAudioTrackBufferSizeUs(): Long = delegate.getAudioTrackBufferSizeUs()
    override fun enableTunnelingV21() = delegate.enableTunnelingV21()
    override fun disableTunneling() = delegate.disableTunneling()
    override fun setOffloadMode(offloadMode: Int) = delegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    override fun setAudioOutputProvider(audioOutputProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) = delegate.setAudioOutputProvider(audioOutputProvider)
    override fun setVolume(volume: Float) = delegate.setVolume(volume)
    override fun pause() = delegate.pause()
    override fun flush() {
        delegate.flush()
        resetTransformState()
    }
    override fun reset() {
        delegate.reset()
        resetTransformState()
    }
    override fun release() = delegate.release()

    private fun initializeTransform() {
        streamInitialized = true
        transform = AudioSyncPcmTransform(
            AudioSyncPcmPlan.create(controller.audioOffsetMs, sampleRateHz, frameSizeBytes),
            frameSizeBytes,
            sampleRateHz,
            pcmEncoding,
        )
    }

    private fun resetTransformState() {
        streamInitialized = false
        transform = null
    }

    private fun pcmBytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
}

@UnstableApi
class AudioSyncRenderersFactory(
    context: Context,
    private val controller: AudioSyncController,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        // Advertise PCM only so encoded passthrough/offload cannot bypass the timestamp adjustment.
        val pcmOnly = androidx.media3.exoplayer.audio.AudioCapabilities(
            intArrayOf(C.ENCODING_PCM_16BIT), 8,
        )
        val delegate = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(pcmOnly)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .setEnableAudioOutputPlaybackParameters(false)
            .build()
        return AudioSyncSink(delegate, controller)
    }
}

internal fun audioSyncOffsetText(offsetMs: Int): String = when {
    offsetMs > 0 -> "声音延后 ${abs(offsetMs)} 毫秒"
    offsetMs < 0 -> "声音提前 ${abs(offsetMs)} 毫秒"
    else -> "无偏移"
}
