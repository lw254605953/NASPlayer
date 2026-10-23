package com.megatron.player

import android.media.MediaCodecList
import android.os.SystemClock
import android.view.Display
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

fun videoHdrLabel(mime: String?, codecs: String?, transfer: Int?): String = when {
    mime == "video/dolby-vision" || codecs?.lowercase(Locale.ROOT)?.let { listOf("dvhe", "dvh1", "dvav", "dva1").any(it::startsWith) } == true -> "杜比视界（片源标识）"
    transfer == C.COLOR_TRANSFER_ST2084 -> "HDR（PQ / HDR10 系列）"
    transfer == C.COLOR_TRANSFER_HLG -> "HDR（HLG）"
    transfer in setOf(C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_SRGB, C.COLOR_TRANSFER_GAMMA_2_2) -> "SDR"
    else -> "未报告"
}
fun videoDimensions(width: Int, height: Int): String = if (width > 0 && height > 0) "${width}×${height}" else "未报告"

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VideoQualityInfo {
    private val recentDrops = ReadWindow(SystemClock::elapsedRealtime)
    private var observed: DecoderCounters? = null
    private var lastDropped = 0
    private var lastRendered = 0
    private var dropped = 0L
    private var rendered = 0L
    private var bufferStarted: Long? = null
    private var bufferDuration = 0L
    private var buffers = 0
    private var decoderName: String? = null
    private var decoderType = "未报告"
    private var displayMode = "未报告"
    private var viewSize = "未报告"
    private var hdrCapabilities = "未报告"
    var inputFormat: Format? = null
    fun decoder(name: String) {
        decoderName = name
        decoderType = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name || it.canonicalName == name }?.let {
                when { it.isHardwareAccelerated -> "硬解"; it.isSoftwareOnly -> "软解"; else -> "未报告" }
            } ?: "未报告"
        }.getOrDefault("未报告")
    }
    fun playbackState(state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (state == androidx.media3.common.Player.STATE_BUFFERING) {
            if (bufferStarted == null) { bufferStarted = now; buffers++ }
        } else { bufferStarted?.let { bufferDuration += now - it }; bufferStarted = null }
    }
    fun sample(player: ExoPlayer) {
        val counters = player.videoDecoderCounters ?: return
        counters.ensureUpdated()
        if (observed !== counters) { observed = counters; lastDropped = 0; lastRendered = 0 }
        val dropDelta = (counters.droppedBufferCount - lastDropped).coerceAtLeast(0)
        dropped += dropDelta; recentDrops.add(dropDelta.toLong())
        rendered += (counters.renderedOutputBufferCount - lastRendered).coerceAtLeast(0)
        lastDropped = counters.droppedBufferCount; lastRendered = counters.renderedOutputBufferCount
    }
    fun display(view: View) {
        viewSize = videoDimensions(view.width, view.height)
        runCatching {
            val display = view.display ?: return
            val mode = display.mode
            displayMode = "${mode.physicalWidth}×${mode.physicalHeight} · ${number(mode.refreshRate.toDouble())} Hz"
            hdrCapabilities = display.hdrCapabilities.supportedHdrTypes.map { when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "杜比视界"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "类型$it"
            } }.joinToString(" / ").ifEmpty { "未报告支持HDR" }
        }
    }
    fun text(player: ExoPlayer, source: String, io: VideoReadMetrics): String {
        sample(player)
        val videoTracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val selected = videoTracks.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
        }
        val format = inputFormat ?: player.videoFormat ?: selected
            ?: videoTracks.firstOrNull { it.length > 0 }?.getTrackFormat(0)
        val color = format?.colorInfo
        val depth = color?.lumaBitdepth?.takeIf { it > 0 }?.let { "$it bit" } ?: "未报告"
        val currentBufferDuration = bufferDuration + (bufferStarted?.let { SystemClock.elapsedRealtime() - it } ?: 0)
        return buildString {
            append("【片源】\n读取通道：$source\n")
            append("视频：${format?.sampleMimeType ?: "未报告"} ${format?.codecs.orEmpty()}\n")
            append("片源分辨率：${videoDimensions(format?.width ?: 0, format?.height ?: 0)}\n")
            append("源标称帧率：${format?.frameRate?.takeIf { it > 0 && it.isFinite() }?.let { number(it.toDouble()) + " fps" } ?: "未报告"}\n")
            append("片源位深：$depth\n片源动态范围：${videoHdrLabel(format?.sampleMimeType, format?.codecs, color?.colorTransfer)}\n")
            append("视频码率（格式报告）：${format?.bitrate?.takeIf { it > 0 }?.let { number(it / 1_000_000.0) + " Mbps" } ?: "未报告"}\n")
            if (io.fileLength > 0 && player.duration > 0) append("文件平均码率（估算，含音频）：${number(io.fileLength * 8.0 / player.duration / 1000)} Mbps\n")
            append("文件读取吞吐（近5秒）：${if (io.hasReads) number(io.reads.perSecond() / 1024 / 1024) + " MiB/s" else "未报告"}\n")
            append("\n【解码与缓冲】\n视频解码器：${decoderName ?: "未启用"} · $decoderType\n")
            append("解码画面：${videoDimensions(player.videoSize.width, player.videoSize.height)}\n")
            append("已渲染：$rendered 帧 · 累计丢帧：$dropped · 近5秒丢帧：${recentDrops.count()}\n")
            append("缓冲次数（含起播和跳转）：$buffers · 缓冲时间：${number(currentBufferDuration / 1000.0)} 秒\n")
            append("已缓冲时长：${number((player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000.0)} 秒\n")
            append("\n【显示】\n当前显示模式：$displayMode\n播放区域：$viewSize\n")
            append("屏幕报告的HDR能力：$hdrCapabilities\n当前HDR实际输出：未确认\n")
        }
    }
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
