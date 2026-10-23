package com.megatron.player

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureDetailsTest {
    @Test fun codecDetailsRetainNumericCodeAndSystemIdentifiers() {
        val result = codecFailureDetails(-1010, "android.media.MediaCodec.error_neg_1010", "c2.android.mpeg4.decoder")
        assertTrue(result.contains("-1010（0xfffffc0e）"))
        assertTrue(result.contains("android.media.MediaCodec.error_neg_1010"))
        assertTrue(result.contains("c2.android.mpeg4.decoder"))
    }

    @Test fun codecDetailsRejectUncontrolledVendorMessages() {
        val result = codecFailureDetails(0, "https://private.example/file.avi?token=secret", "/storage/private/file.avi")
        assertEquals("系统错误码：0（0x0）", result)
        assertEquals(result, codecFailureDetails(0, "x".repeat(161)))
    }

    @Test fun retainsNestedTimeoutInsteadOfOnlyGenericIoError() {
        val error = IOException("Source error", SocketTimeoutException("Read timed out"))
        val result = playbackFailureCauses(error)
        assertTrue(result.contains("IOException"))
        assertTrue(result.contains("SocketTimeoutException: 连接或读取超时"))
    }

    @Test fun recordsPlexHttpAndPathFailuresPrecisely() {
        assertEquals("HTTP 401", playbackFailureReason(IOException("Plex 请求失败（HTTP 401）")))
        assertEquals("Plex 媒体路径无效", playbackFailureReason(IOException("Plex 媒体路径无效")))
    }

    @Test fun arbitraryMessagesNeverExposeHostCredentialsOrFilePaths() {
        val sensitive = "https://user:password@private.example:32400/library/parts/123/file.mkv?X-Plex-Token=secret"
        val result = playbackFailureCauses(IOException(sensitive, UnknownHostException(sensitive)))
        assertTrue(result.contains("服务器域名无法解析"))
        listOf("private.example", "password", "secret", "file.mkv", "X-Plex-Token").forEach {
            assertFalse("Sensitive field leaked: $it", result.contains(it))
        }
    }

    @Test fun retainsOnlyBoundedNumericContainerParameters() {
        assertEquals("ContentCompAlgo 0 not supported", playbackFailureReason(IOException("ContentCompAlgo 0 not supported")))
        assertEquals("ContentCompAlgo 0 not supported", playbackFailureReason(IOException("ContentCompAlgo 0 not supported {contentIsMalformed=true, dataType=1}")))
        assertEquals("", playbackFailureReason(IOException("ContentCompAlgo https://private.example/token not supported")))
        assertEquals("", playbackFailureReason(IOException("ContentCompAlgo 0 not supported https://private.example")))
    }

    @Test fun cyclesCannotBlockCopyingDiagnostics() {
        val first = IOException("one")
        val second = IOException("two", first)
        first.initCause(second)
        assertEquals(2, playbackFailureCauses(first).lineSequence().count())
    }
}
