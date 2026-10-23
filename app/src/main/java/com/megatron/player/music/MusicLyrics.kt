package com.megatron.player.music

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

internal data class LyricLine(val timeMs: Long, val text: String)
internal data class SongLyrics(val lines: List<LyricLine>, val timed: Boolean) {
    fun activeLine(positionMs: Long): Int {
        if (!timed) return -1
        var low = 0; var high = lines.lastIndex; var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) { result = mid; low = mid + 1 } else high = mid - 1
        }
        return result
    }
}

internal object MusicLyricsParser {
    const val MAX_BYTES = 256 * 1024
    private val stamp = Regex("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES)
        return when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.toString(Charsets.UTF_16BE)
            else -> runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { bytes.toString(Charset.forName("GB18030")) }
        }.trimStart('\uFEFF').replace("\u0000", "")
    }
    fun parse(text: String): SongLyrics? {
        if (text.toByteArray().size > MAX_BYTES) return null
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-3_600_000, 3_600_000) ?: 0
        val timed = mutableListOf<LyricLine>(); val plain = mutableListOf<String>()
        text.lineSequence().take(5000).forEach { raw ->
            val line = raw.trim().trimStart('\uFEFF')
            val matches = stamp.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val words = stamp.replace(line, "").trim()
                matches.forEach { match ->
                    val seconds = match.groupValues[2].toInt()
                    if (seconds < 60) {
                        val fraction = match.groupValues[3].padEnd(3, '0').toIntOrNull() ?: 0
                        val time = match.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction - offset
                        timed += LyricLine(time.coerceAtLeast(0), words)
                    }
                }
            } else if (line.isNotBlank() && !line.matches(Regex("\\[[A-Za-z]+:.*]"))) plain += line
        }
        if (timed.any { it.text.isNotBlank() }) return SongLyrics(timed.groupBy { it.timeMs }.toSortedMap().map { (time, rows) ->
            LyricLine(time, rows.map { it.text }.distinct().joinToString("\n"))
        }, true)
        return plain.takeIf { it.isNotEmpty() }?.let { SongLyrics(it.map { line -> LyricLine(-1, line) }, false) }
    }
}
