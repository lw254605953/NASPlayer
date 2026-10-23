package com.megatron.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.megatron.player.plex.PlexPlaybackFailure
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class PlaybackFailureGuide(val title: String, val message: String, val action: String = "重试播放")

/** Classify controlled error details. Never show raw URLs, exception messages or authentication. */
fun playbackFailureGuide(error: Throwable?, stage: String): PlaybackFailureGuide {
    val chain = mutableListOf<Throwable>()
    var next = error
    while (next != null && chain.size < 12 && chain.none { it === next }) { chain += next; next = next.cause }
    val plex = chain.filterIsInstance<PlexPlaybackFailure>().firstOrNull()
    val messages = chain.map { it.message.orEmpty() }
    val webdav = plex?.userMessage?.contains("WebDAV") == true || messages.any { it.startsWith("WebDAV") } || stage.contains("WebDAV")
    val metadata = plex?.userMessage?.contains("Plex 文件资料") == true || stage.contains("授权配置")
    val status = plex?.httpStatus ?: chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        ?: messages.firstNotNullOfOrNull { Regex("(?:WebDAV|Plex) 请求失败（HTTP ([0-9]{3})）").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: messages.firstNotNullOfOrNull { Regex("HTTP ([0-9]{3})").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
    return when {
        metadata -> PlaybackFailureGuide("Plex 资料无法读取", "请在设置中检查 Plex 授权。影片资料与 WebDAV 视频连接分别维护。", "重试读取")
        status in setOf(401, 403) && webdav -> PlaybackFailureGuide("WebDAV 读取权限不足", "请在设置中检查 WebDAV 账号、密码及共享目录权限。", "重试读取")
        status in setOf(401, 403) && plex == null && !stage.contains("Plex") ->
            PlaybackFailureGuide("视频地址读取权限不足", "该视频链接需要授权或已失效。请检查链接，或通过已配置的片源打开文件。", "重试读取")
        status in setOf(401, 403) -> PlaybackFailureGuide("Plex 拒绝读取", "请检查 Plex 授权与服务器返回的许可说明，可在诊断中查看具体阶段。", "重试读取")
        status == 404 || plex?.userMessage?.let { it.contains("找到") || it.contains("找不到") || it.contains("未提供视频文件位置") } == true ->
            PlaybackFailureGuide("找不到影片文件", if (webdav) "请确认 WebDAV 能浏览到这部影片，文件未移动，并刷新 Plex 影片资料后重试。" else "文件可能已移动，请检查视频地址或重新选择文件。", "重新定位文件")
        plex?.userMessage?.contains("多个目录") == true -> PlaybackFailureGuide("存在多个同名文件", "WebDAV 返回多个无法区分的文件。请从文件频道选择实际文件播放，或检查 Plex 文件资料。", "重新定位文件")
        messages.any { it.contains("找不到 WebDAV 片源配置") || it.contains("连接已删除") || it.contains("找不到 Plex 服务器配置") } ->
            PlaybackFailureGuide("片源连接需要配置", "请返回设置重新选择 Plex 或 WebDAV 视频连接。", "重试读取")
        chain.any { it is SocketTimeoutException || it is UnknownHostException || it is ConnectException } ->
            PlaybackFailureGuide("无法连接服务器", "请检查手机网络与服务器是否可访问，恢复连接后重试。")
        chain.any { it is SSLException } -> PlaybackFailureGuide("安全连接失败", "请检查服务器 HTTPS 地址及证书配置，然后重试。")
        chain.any { it is FileNotFoundException || it is SecurityException } -> PlaybackFailureGuide("文件读取失败", "文件可能已移动，或本机读取授权已失效。请重新选择文件。")
        chain.filterIsInstance<PlaybackException>().any { it.errorCode in 4000..4999 } ->
            PlaybackFailureGuide("当前音视频无法解码", "可尝试切换音轨或其他播放版本，并复制诊断信息反馈。", "重试播放")
        chain.filterIsInstance<PlaybackException>().any { it.errorCode in 3000..3999 } ->
            PlaybackFailureGuide("媒体或字幕解析失败", "请尝试移除外挂字幕或切换影片版本，再重试播放。")
        status != null -> PlaybackFailureGuide("服务器读取失败", "服务器返回 HTTP $status。请稍后重试，可查看诊断确认读取阶段。")
        else -> PlaybackFailureGuide("暂时无法播放", "请重试；若仍失败，查看并复制诊断信息以确认文件、连接或解码问题。")
    }
}
