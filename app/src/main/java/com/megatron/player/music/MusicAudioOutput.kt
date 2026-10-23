package com.megatron.player.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.lang.ref.WeakReference
import java.util.Locale

/** Observes the music output only; never changes system volume, routing or gain. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusicAudioOutput(context: Context) {
    @Volatile private var track = WeakReference<AudioTrack>(null)
    @Volatile private var decoded: Format? = null
    @Volatile var decoderName: String = "尚未启用"

    fun factory(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink {
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                .setAudioTrackProvider(object : DefaultAudioSink.AudioTrackProvider {
                    override fun getAudioTrack(config: AudioSink.AudioTrackConfig, attributes: AudioAttributes, audioSessionId: Int, context: Context?): AudioTrack =
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, audioSessionId, context).also {
                            track = WeakReference(it)
                        }
                }).build()
            return object : ForwardingAudioSink(sink) {
                override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                    super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    decoded = inputFormat
                }
            }
        }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableAudioFloatOutput(true)

    fun describe(player: ExoPlayer): String = buildString {
        val input = player.audioFormat
        val current = player.currentMediaItem?.musicTrack()
        appendLine("解码器：$decoderName")
        appendLine("源文件：${current?.format.orEmpty()} · ${current?.bitDepth?.takeIf { it > 0 }?.let { "$it bit · " }.orEmpty()}${rate(input?.sampleRate ?: current?.sampleRate ?: 0)}")
        val pcm = decoded
        appendLine("解码后 PCM：${encoding(pcm?.pcmEncoding ?: C.ENCODING_INVALID)} · ${rate(pcm?.sampleRate ?: 0)} · ${pcm?.channelCount?.takeIf { it > 0 }?.let { "$it 声道" } ?: "声道未知"}")
        // Track release occurs on the playback thread; diagnostics must never interrupt playback.
        val output = runCatching {
            track.get()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let {
                "${encoding(it.audioFormat)} · ${rate(it.sampleRate)} · ${it.channelCount} 声道" to it.routedDevice?.let(::deviceName)
            }
        }.getOrNull()
        appendLine("AudioTrack：${output?.first ?: "尚未创建"}")
        appendLine("实际路由：${output?.second ?: "未在播放或系统未返回"}")
        appendLine("高精度 PCM：已启用 · 速度：${player.playbackParameters.speed} · DSP：未启用")
        append("AudioTrack 信息表示提交给 Android 的音频，不代表扬声器/DAC 最终精度或独占输出。")
    }

    private fun rate(value: Int) = if (value > 0) "${String.format(Locale.ROOT, "%.1f", value / 1000.0)} kHz" else "采样率未知"
    private fun encoding(value: Int) = when (value) {
        C.ENCODING_PCM_8BIT -> "PCM 8 bit"
        C.ENCODING_PCM_16BIT -> "PCM 16 bit"
        C.ENCODING_PCM_24BIT -> "PCM 24 bit"
        C.ENCODING_PCM_32BIT -> "PCM 32 bit"
        C.ENCODING_PCM_FLOAT -> "PCM Float 32 bit"
        else -> "未知 / 非 PCM"
    }
    private fun deviceName(device: AudioDeviceInfo) = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "手机扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙设备"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频设备"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI 音频"
        else -> "系统音频设备（类型 ${device.type}）"
    }
}
