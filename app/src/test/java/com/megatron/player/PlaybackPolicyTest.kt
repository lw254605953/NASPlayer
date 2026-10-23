package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun acceptsPrivateServerAndPreservesQuery() {
        assertEquals("http://192.168.1.20:8080/video.mkv?token=demo", validatedHttpUrl("  http://192.168.1.20:8080/video.mkv?token=demo  "))
    }
    @Test fun rejectsUnsafeOrMalformedInputs() {
        listOf("", "file:///etc/passwd", "javascript:alert(1)", "https:///movie.mp4", "https://user:pass@example.com/movie", "https://example.com:99999/movie", "https://example.com/movie\nHeader: value").forEach {
            assertNull("Must reject: $it", validatedHttpUrl(it))
        }
    }
    @Test fun resumesKnownAndUnknownDurationWithoutCrossingTheEnd() {
        assertEquals(120_000L, resumePositionMs(120_000L, 1_800_000L))
        assertEquals(120_000L, resumePositionMs(120_000L, 0L))
        assertEquals(0L, resumePositionMs(1_790_000L, 1_800_000L))
        assertEquals(0L, resumePositionMs(2_000_000L, 1_800_000L))
        assertEquals(0L, resumePositionMs(-100L, 1_800_000L))
    }
    @Test fun detectsSupportedSubtitleExtensionsCaseInsensitively() {
        assertEquals("application/x-subrip", subtitleMimeType("中文字幕.SRT"))
        assertEquals("text/vtt", subtitleMimeType("movie.vtt"))
        assertEquals("text/x-ssa", subtitleMimeType("movie.ass"))
        assertEquals("text/x-ssa", subtitleMimeType("movie.SSA"))
        assertNull(subtitleMimeType("movie.pdf"))
    }
}
