package com.megatron.player.music

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MusicTagParserTest {
    private fun be(n: Int, width: Int) = ByteArray(width) { (n ushr ((width - it - 1) * 8)).toByte() }
    private fun le(n: Int) = ByteArray(4) { (n ushr (it * 8)).toByte() }
    private fun block(type: Int, data: ByteArray, last: Boolean = false) = byteArrayOf((type or if (last) 128 else 0).toByte()) + be(data.size, 3) + data
    private fun comments(): ByteArray {
        val values = listOf("TITLE=扫描歌曲", "ARTIST=测试歌手", "ALBUM=测试专辑")
        return le(0) + le(values.size) + values.fold(ByteArray(0)) { bytes, value ->
            val text = value.toByteArray(); bytes + le(text.size) + text
        }
    }
    @Test fun flacEmbeddedTimedLyricsAreReadWithoutTruncatingAt2048Characters() {
        val lyrics = "[00:00.00]" + "中文歌词".repeat(600)
        val value = ("LYRICS=" + lyrics).toByteArray()
        val comment = le(0) + le(1) + le(value.size) + value
        val file = "fLaC".toByteArray() + block(4, comment, true)
        val tags = MusicTagParser.read("FLAC", file.size.toLong()) { start, count -> file.copyOfRange(start.toInt(), minOf(file.size,start.toInt()+count)) }
        assertEquals(lyrics,tags.lyrics)
    }
    @Test fun id3UsltEmbeddedTextUsesItsDeclaredEncoding() {
        val lyric = "[00:01.00]中文歌词"
        val data = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + lyric.toByteArray()
        val frame = "USLT".toByteArray() + be(data.size,4) + byteArrayOf(0,0) + data
        val length = frame.size
        val size = byteArrayOf(((length ushr 21) and 127).toByte(),((length ushr 14) and 127).toByte(),((length ushr 7) and 127).toByte(),(length and 127).toByte())
        val file = "ID3".toByteArray() + byteArrayOf(3,0,0) + size + frame
        val tags = MusicTagParser.read("MP3", file.size.toLong()) { start,count -> file.copyOfRange(start.toInt(),minOf(file.size,start.toInt()+count)) }
        assertEquals(lyric,tags.lyrics)
    }
    @Test fun flacSkipsLargePaddingAndIndexesLabelsAndLargeCoverBeforePlayback() {
        val art = ByteArray(1_200_000) { (it % 251).toByte() }
        val mime = "image/jpeg".toByteArray()
        val picture = be(3, 4) + be(mime.size, 4) + mime + be(0, 4) + ByteArray(16) + be(art.size, 4) + art
        val file = "fLaC".toByteArray() + block(0, ByteArray(34)) + block(1, ByteArray(3_000_000)) + block(4, comments()) + block(6, picture, true)
        var transferred = 0L
        val tags = MusicTagParser.read("FLAC", file.size.toLong()) { start, count ->
            transferred += count; file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count))
        }
        assertEquals("扫描歌曲", tags.title)
        assertEquals("测试歌手", tags.artist)
        assertEquals("测试专辑", tags.album)
        assertArrayEquals(art, tags.art)
        assertTrue("Must not download padding/audio: $transferred", transferred < 1_400_000)
    }
    @Test fun ordinaryFlacCommentsStayAvailable() {
        val file = "fLaC".toByteArray() + block(0, ByteArray(34)) + block(4, comments(), true)
        val tags = MusicTagParser.read("FLAC", file.size.toLong()) { start, count -> file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count)) }
        assertEquals("测试歌手", tags.artist)
    }

    @Test fun truncatedFlacMetadataRemainsRetryableInsteadOfBeingMarkedAsComplete() {
        val file = "fLaC".toByteArray() + byteArrayOf(132.toByte(), 0, 0, 100) + byteArrayOf(1, 2)
        try {
            MusicTagParser.read("FLAC", file.size.toLong()) { start, count -> file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count)) }
            fail("Truncated metadata must be reported")
        } catch (_: IOException) { }
    }

    @Test fun mp3ExtendedHeaderUsesSameArtistParserAsPlayback() {
        val body = byteArrayOf(0) + "Extended Artist".toByteArray()
        val frame = "TPE1".toByteArray() + be(body.size, 4) + byteArrayOf(0, 0) + body
        val extended = be(6, 4) + ByteArray(6)
        val size = extended.size + frame.size
        val syncSize = byteArrayOf((size ushr 21).toByte(), ((size ushr 14) and 127).toByte(), ((size ushr 7) and 127).toByte(), (size and 127).toByte())
        val file = "ID3".toByteArray() + byteArrayOf(3, 0, 64) + syncSize + extended + frame
        val tags = MusicTagParser.read("MP3", file.size.toLong()) { start, count -> file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count)) }
        assertEquals("Extended Artist", tags.artist)
    }
}
