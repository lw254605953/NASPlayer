package com.megatron.player

import android.media.MediaCodec
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only controlled descriptions are copied. Exception messages can contain URLs and tokens. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun playbackFailureReason(error: Throwable): String = when (error) {
    is MediaCodecDecoderException -> codecFailureDetails(error.errorCode, error.diagnosticInfo, error.codecInfo?.name)
    is MediaCodec.CodecException -> codecFailureDetails(error.errorCode, error.diagnosticInfo) +
        " · 可恢复：${if (error.isRecoverable) "是" else "否"} · 临时错误：${if (error.isTransient) "是" else "否"}"
    is com.megatron.player.plex.PlexPlaybackFailure -> error.description()
    is SocketTimeoutException -> "连接或读取超时"
    is UnknownHostException -> "服务器域名无法解析"
    is ConnectException -> "无法建立服务器连接"
    is SSLException -> "TLS 连接失败"
    is EOFException -> "内容提前结束"
    is FileNotFoundException -> "资源不存在或没有读取权限"
    is SecurityException -> "没有读取权限"
    is InterruptedIOException -> "读取被中断"
    else -> {
        val message = error.message.orEmpty().replace(MEDIA_ERROR_METADATA, "")
        when {
            message in safeMediaErrors || SAFE_CONTAINER_PARAMETER.matches(message) -> message
            PLEX_HTTP.matches(message) -> "HTTP ${PLEX_HTTP.matchEntire(message)!!.groupValues[1]}"
            WEBDAV_HTTP.matches(message) -> "WebDAV HTTP ${WEBDAV_HTTP.matchEntire(message)!!.groupValues[1]}"
            message.equals("Canceled", true) || message.equals("Socket closed", true) -> "连接已关闭或请求已取消"
            message.contains("Connection reset", true) -> "连接被重置"
            message.contains("CLEARTEXT", true) -> "系统拒绝明文 HTTP 连接"
            else -> ""
        }
    }
}

/** Codec identifiers are system tokens; arbitrary vendor messages must not enter copied diagnostics. */
internal fun codecFailureDetails(code: Int, diagnostic: String?, decoder: String? = null): String = buildString {
    append("系统错误码：$code（0x${Integer.toHexString(code)}）")
    diagnostic?.takeIf { SAFE_CODEC_TOKEN.matches(it) }?.let { append(" · 诊断码：$it") }
    decoder?.takeIf { SAFE_CODEC_TOKEN.matches(it) }?.let { append(" · 解码器：$it") }
}

fun playbackFailureCauses(error: Throwable): String {
    val chain = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (chain.size < 8) {
        val current = next ?: break
        if (chain.any { it === current }) break
        chain += current
        next = current.cause
    }
    return chain.joinToString("\n") { cause ->
        val reason = playbackFailureReason(cause)
        buildString {
            append(cause.javaClass.simpleName)
            if (reason.isNotEmpty()) append(": $reason")
            cause.stackTrace.firstOrNull()?.let { frame ->
                append(" [${frame.className}.${frame.methodName}:${frame.lineNumber}]")
            }
        }
    }
}

private val MEDIA_ERROR_METADATA = Regex(" \\{contentIsMalformed=(?:true|false), dataType=-?[0-9]+\\}$")
private val SAFE_CODEC_TOKEN = Regex("[A-Za-z0-9_.-]{1,160}")
private val SAFE_CONTAINER_PARAMETER = Regex("(?:ContentCompAlgo|ContentEncodingOrder|ContentEncodingScope|ContentEncAlgo|AESSettingsCipherMode) -?[0-9]{1,20} not supported")
private val PLEX_HTTP = Regex("Plex 请求失败（HTTP ([0-9]{3})）")
private val WEBDAV_HTTP = Regex("WebDAV 请求失败（HTTP ([0-9]{3})）")
private val safeMediaErrors = setOf(
    "AVI sample exceeds compatibility buffer limit", "AVI sample boundaries are invalid",
    "Multiple content compression layers not supported",
    "Zlib compression is supported only for plain PGS or Vobsub subtitle frames",
    "找不到 Plex 服务器配置", "Plex 服务器配置无效", "Plex 服务器地址无效",
    "Plex 服务器地址格式不受支持", "Plex 媒体路径无效", "Plex 读取位置无效",
    "Plex 读取范围过大", "Plex 服务不支持从指定位置读取", "Plex 返回空内容",
    "Plex 返回的范围无效", "Plex 内容提前结束", "Plex 重定向超出服务器范围",
    "Plex 重定向无效", "Plex 重定向次数过多", "数据源未打开",
)
