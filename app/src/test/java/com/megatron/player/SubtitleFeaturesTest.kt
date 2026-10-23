package com.megatron.player

import androidx.media3.exoplayer.Renderer
import com.megatron.player.source.BrowserEntry
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SubtitleFeaturesTest {
    @Test fun externalTrackIdentitySurvivesReloadButRejectsEmbeddedSameName() {
        val uri = "mpdav/nas/电影/影片.zh.srt"
        val id = externalSubtitleTrackId(uri)
        assertEquals(id, externalSubtitleTrackId(uri))
        assertTrue(isExternalSubtitleFormat(id, "影片.zh.srt", id, "影片.zh.srt"))
        assertFalse(isExternalSubtitleFormat("embedded-zh", "影片.zh.srt", id, "影片.zh.srt"))
        assertFalse(isExternalSubtitleFormat(externalSubtitleTrackId("mpdav/other/影片.zh.srt"), "影片.zh.srt", id, "影片.zh.srt"))
        assertFalse(isExternalSubtitleFormat(id, "旧字幕.srt", id, "影片.zh.srt"))
    }

    @Test fun subtitlesUseExactSiblingPathsAndDelimitedLanguageSuffixes() {
        val names = listOf("影片.srt", "影片.zh-Hans.ass", "影片 (2).srt", "影片2.srt", "影片.mkv", "影片.zh.srt")
        val entries = names.map { BrowserEntry(it, "电影/$it", false) } + listOf(
            BrowserEntry("影片.srt", "其他/影片.srt", false), BrowserEntry("影片.srt", "电影/影片.srt", true),
            BrowserEntry("影片.srt", "电影/子目录/影片.srt", false))
        assertEquals(listOf("影片.srt", "影片.zh-Hans.ass", "影片.zh.srt"), sameNameSubtitles("电影/影片.mkv", entries).map { it.name })
    }

    @Test fun literalPercentAndUnicodeAreMatchedWithoutUrlDecoding() {
        val entries = listOf(BrowserEntry("100%25 中文.en.vtt", "100%25 中文.en.vtt", false),
            BrowserEntry("100% 中文.srt", "100% 中文.srt", false))
        assertEquals("100%25 中文.en.vtt", sameNameSubtitles("100%25 中文.mkv", entries).single().name)
        assertTrue(sameNameSubtitles(".mkv", entries).isEmpty())
    }

    @Test fun offsetChangesRealTextRendererSchedulingWithoutChangingOtherArguments() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            when (method.returnType) { java.lang.Long.TYPE -> 20_000L; java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; else -> null }
        } as Renderer
        var offset = 500
        val renderer = SubtitleTimingRenderer(delegate) { offset }
        renderer.render(2_000_000, 9_000_000)
        assertEquals("render" to listOf(1_500_000L, 9_000_000L), calls.last())
        renderer.resetPosition(2_000_000, true)
        assertEquals("resetPosition" to listOf(1_500_000L, true), calls.last())
        offset = -500
        renderer.render(2_000_000, 9_000_000)
        assertEquals("render" to listOf(2_500_000L, 9_000_000L), calls.last())
        offset = 0
        renderer.render(2_000_000, 9_000_000)
        assertEquals("render" to listOf(2_000_000L, 9_000_000L), calls.last())
    }
}
