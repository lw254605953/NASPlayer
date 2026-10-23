package com.megatron.player

import com.megatron.player.plex.PlexPlaybackFailure
import java.io.IOException
import java.net.ConnectException
import org.junit.Assert.*
import org.junit.Test

class PlayerFailureGuideTest {
    @Test fun classifiesMetadataDavAndNetworkWithoutLeakingRawErrors() {
        assertEquals("Plex 资料无法读取", playbackFailureGuide(PlexPlaybackFailure("无法读取 Plex 文件资料", httpStatus = 401), "读取 WebDAV 真实目录").title)
        assertEquals("WebDAV 读取权限不足", playbackFailureGuide(IOException("WebDAV 请求失败（HTTP 403）"), "等待媒体读取").title)
        assertEquals("找不到影片文件", playbackFailureGuide(PlexPlaybackFailure("WebDAV 找不到视频文件", httpStatus = 404), "连接 WebDAV").title)
        assertEquals("无法连接服务器", playbackFailureGuide(IOException("https://secret?token=hidden", ConnectException("secret")), "等待媒体读取").title)
        val safe = playbackFailureGuide(IOException("https://private?X-Plex-Token=hidden"), "等待媒体读取")
        assertFalse(safe.message.contains("hidden"))
        assertFalse(safe.message.contains("private"))
    }
    @Test fun directHttpAuthorizationIsNotMisidentifiedAsPlex() {
        val failure = IOException("HTTP 401")
        assertEquals("视频地址读取权限不足", playbackFailureGuide(failure, "等待媒体读取").title)
    }
}
