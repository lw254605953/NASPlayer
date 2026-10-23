package com.megatron.player.music

import org.junit.Assert.*
import org.junit.Test

class MusicLyricsTest {
    @Test fun multipleTimestampsFractionOffsetAndBackwardSeek() {
        val lyrics = MusicLyricsParser.parse("[ar:测试]\n[offset:100]\n[00:01.20][00:05.200]第一句\n[00:03.003]第二句")!!
        assertTrue(lyrics.timed)
        assertEquals(listOf(1100L, 2903L, 5100L), lyrics.lines.map { it.timeMs })
        assertEquals(-1, lyrics.activeLine(0)); assertEquals(2, lyrics.activeLine(9000)); assertEquals(0, lyrics.activeLine(1200))
    }
    @Test fun translationsAtSameTimeStayTogetherAndBlankLinesClearThePriorLine() {
        val lyrics = MusicLyricsParser.parse("[00:01]Hello\n[00:01]你好\n[00:02]\n[00:04]Again")!!
        assertEquals("Hello\n你好", lyrics.lines[0].text)
        assertEquals("", lyrics.lines[lyrics.activeLine(3000)].text)
    }
    @Test fun plainLyricsHaveNoInventedTimingsAndMissingLyricsStayHidden() {
        assertFalse(MusicLyricsParser.parse("第一行\n第二行")!!.timed)
        assertNull(MusicLyricsParser.parse("[ar:名字]\n[ti:歌名]\n"))
        assertNull(MusicLyricsParser.parse(" "))
    }
    @Test fun chineseLegacyAndUtf16BomAreDecoded() {
        assertEquals("中文歌词", MusicLyricsParser.decode("中文歌词".toByteArray(charset("GB18030"))))
        assertEquals("中文歌词", MusicLyricsParser.decode(byteArrayOf(-1,-2) + "中文歌词".toByteArray(Charsets.UTF_16LE)))
    }
    @Test fun oversizedLyricsDoNotEnterTheUi() {
        assertNull(MusicLyricsParser.parse("字".repeat(MusicLyricsParser.MAX_BYTES)))
    }
}
