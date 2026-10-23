package com.megatron.player.music

import java.nio.charset.Charset
import java.io.IOException

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object MusicTagParser {
    fun read(format: String, sizeBytes: Long, readRange: (Long, Int) -> ByteArray): Tags {
        val header = readRange(0, 64 * 1024)
        val tags = when {
            header.size >= 10 && header.ascii(0, 3) == "ID3" -> {
                val size = syncSafe(header, 6) + 10
                if (size > MAX_TAG) throw IOException("歌曲标签超过读取上限")
                id3(if (size > header.size) readRange(0, size).also {
                    if (it.size != size) throw IOException("歌曲标签不完整")
                } else header)
            }
            header.ascii(0, 4) == "fLaC" -> flacBlocks(header, sizeBytes, readRange)
            header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WAVE" -> wav(header, sizeBytes)
            header.ascii(0, 4) == "MAC " -> apeHeader(header)
            else -> Tags()
        }
        if (format == "APE" && sizeBytes > 32) {
            var end = sizeBytes
            var footer = readRange(end - 32, 32)
            if (footer.ascii(0, 8) != "APETAGEX" && end > 160) {
                val id3v1 = readRange(end - 128, 128)
                if (id3v1.ascii(0, 3) == "TAG") { end -= 128; footer = readRange(end - 32, 32) }
            }
            if (footer.ascii(0, 8) == "APETAGEX") {
                val length = footer.le32(12).toLong()
                if (length in 32..MAX_TAG.toLong() && length <= end) {
                    val data = readRange(end - length, length.toInt())
                    if (data.size != length.toInt()) throw IOException("歌曲标签不完整")
                    tags.merge(ape(data))
                }
            }
        }
        return tags
    }

    /** Read only useful blocks; FLAC padding may be many MiB and is never downloaded. */
    private fun flacBlocks(initial: ByteArray, fileSize: Long, readRange: (Long, Int) -> ByteArray): Tags {
        val result = Tags()
        var position = 4L
        var budget = 24 * 1024 * 1024
        fun read(start: Long, count: Int): ByteArray {
            if (count > budget) throw IOException("歌曲标签超过读取上限")
            val data = if (start + count <= initial.size) initial.copyOfRange(start.toInt(), start.toInt() + count)
                else { budget -= count; readRange(start, count) }
            if (data.size != count) throw IOException("FLAC标签不完整")
            return data
        }
        repeat(1024) {
            val block = read(position, 4)
            val type = block[0].u() and 127
            val length = block.be(1, 3)
            val next = position + 4 + length
            if (fileSize > 0 && next > fileSize) throw IOException("FLAC标签长度无效")
            val useful = type == 0 || type == 4 || (type == 6 && length <= MAX_ART + 2048)
            if (useful) {
                if (length > MAX_TAG) throw IOException("歌曲标签超过读取上限")
                val parsed = flac("fLaC".toByteArray() + byteArrayOf((type or 128).toByte()) + block.copyOfRange(1, 4) + read(position + 4, length))
                result.merge(parsed)
                if (parsed.rate > 0) result.rate = parsed.rate
                if (parsed.bits > 0) result.bits = parsed.bits
                if (parsed.duration > 0) result.duration = parsed.duration
            }
            position = next
            if (block[0].u() and 128 != 0) return result
        }
        throw IOException("FLAC标签块过多")
    }

    data class Tags(var title: String = "", var artist: String = "", var album: String = "", var track: Int = 0,
        var disc: Int = 0, var rate: Int = 0, var bits: Int = 0, var duration: Long = 0, var art: ByteArray? = null, var lyrics: String = "") {
        fun set(key: String, text: String) {
            if (key.uppercase() in setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")) {
                if (text.toByteArray().size <= MusicLyricsParser.MAX_BYTES) lyrics = text.trim('\u0000', ' ', '\r', '\n')
                return
            }
            val clean = text.trim('\u0000', ' ', '\n', '\r').take(2048); when (key.uppercase()) {
            "TITLE", "TIT2", "TT2", "INAM" -> title = clean
            "ARTIST", "TPE1", "TP1", "IART" -> artist = clean
            "ALBUM", "TALB", "TAL", "IPRD" -> album = clean
            "TRACK", "TRACKNUMBER", "TRCK", "TRK", "ITRK" -> track = clean.substringBefore('/').toIntOrNull() ?: 0
            "DISC", "DISCNUMBER", "TPOS", "TPA" -> disc = clean.substringBefore('/').toIntOrNull() ?: 0
        } }
        fun merge(other: Tags) { if (other.title.isNotBlank()) title = other.title; if (other.artist.isNotBlank()) artist = other.artist
            if (other.album.isNotBlank()) album = other.album; if (other.track > 0) track = other.track
            if (other.disc > 0) disc = other.disc; if (other.art != null) art = other.art; if (other.lyrics.isNotBlank()) lyrics = other.lyrics }
    }

    private fun id3(bytes: ByteArray): Tags {
        val result = Tags()
        // Use the same parser as playback, including extended and unsynchronised ID3 headers.
        val metadata = androidx.media3.extractor.metadata.id3.Id3Decoder().decode(bytes, bytes.size)
            ?: throw IOException("ID3标签无法解析")
        for (i in 0 until metadata.length()) {
            when (val frame = metadata[i]) {
                is androidx.media3.extractor.metadata.id3.TextInformationFrame -> result.set(if (frame.id == "TXXX") frame.description.orEmpty() else frame.id, frame.values.joinToString("\n"))
                is androidx.media3.extractor.metadata.id3.BinaryFrame -> if (frame.id in setOf("USLT", "ULT")) readUslt(frame.data)?.let { result.set("LYRICS", it) }
                is androidx.media3.extractor.metadata.id3.ApicFrame -> if (frame.pictureData.size <= MAX_ART &&
                    (frame.pictureType == 3 || result.art == null)) result.art = frame.pictureData
            }
        }
        return result
    }
    private fun readUslt(bytes: ByteArray): String? {
        if (bytes.size < 5 || bytes.size > MusicLyricsParser.MAX_BYTES) return null
        val encoding = bytes[0].toInt()
        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> return null }
        var pos = 4
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        while (pos + width <= bytes.size) {
            if (bytes[pos] == 0.toByte() && (width == 1 || bytes[pos + 1] == 0.toByte())) {
                return bytes.copyOfRange(pos + width, bytes.size).toString(charset).trim('\u0000', '\uFEFF')
            }
            pos += width
        }
        return null
    }
    private fun flac(b: ByteArray): Tags {
        val t = Tags(); var pos = 4
        while (pos + 4 <= b.size) {
            val type = b[pos].toInt() and 127; val last = b[pos].toInt() and 128 != 0
            val length = b.be(pos + 1, 3); pos += 4
            if (length < 0 || length > b.size - pos) break
            if (type == 0 && length >= 34) {
                val p = pos + 10
                t.rate = (b[p].u() shl 12) or (b[p + 1].u() shl 4) or (b[p + 2].u() shr 4)
                t.bits = (((b[p + 2].u() and 1) shl 4) or (b[p + 3].u() shr 4)) + 1
                val samples = ((b[p + 3].u().toLong() and 15L) shl 32) or (b.be(p + 4, 4).toLong() and 0xffffffffL)
                if (t.rate > 0) t.duration = samples * 1000 / t.rate
            }
            if (type == 4) {
                var p = pos
                val vendor = b.le32(p); p += 4
                if (vendor >= 0 && vendor <= length - 8) {
                    p += vendor; val count = b.le32(p).coerceIn(0, 1000); p += 4
                    repeat(count) {
                        if (p + 4 <= pos + length) {
                            val size = b.le32(p); p += 4
                            if (size >= 0 && size <= pos + length - p) {
                                val s = b.copyOfRange(p, p + size).toString(Charsets.UTF_8); p += size
                                t.set(s.substringBefore('='), s.substringAfter('=', ""))
                            } else p = pos + length
                        }
                    }
                }
            }
            if (type == 6 && length <= MAX_ART + 2048) {
                var p = pos + 4
                val mimeLen = b.be(p, 4); p += 4
                if (mimeLen in 0..1024 && p + mimeLen + 4 <= pos + length) {
                    p += mimeLen; val desc = b.be(p, 4); p += 4
                    if (desc >= 0 && desc <= pos + length - p - 20) {
                        p += desc + 16; val size = b.be(p, 4); p += 4
                        if (size in 1..MAX_ART && size <= pos + length - p) t.art = b.copyOfRange(p, p + size)
                    }
                }
            }
            pos += length; if (last) break
        }
        return t
    }
    private fun wav(b: ByteArray, totalSize: Long): Tags {
        val t = Tags(); var pos = 12; var bytesPerSecond = 0L
        while (pos + 8 <= b.size) {
            val key = b.ascii(pos, 4); val size = b.le32(pos + 4).toLong() and 0xffffffffL; pos += 8
            if (key == "data") { if (bytesPerSecond > 0) t.duration = minOf(size, (totalSize - pos).coerceAtLeast(0)) * 1000 / bytesPerSecond; break }
            if (size > b.size - pos) break
            if (key == "fmt " && size >= 16) { t.rate = b.le32(pos + 4); bytesPerSecond = b.le32(pos + 8).toLong() and 0xffffffffL; t.bits = b.le16(pos + 14) }
            if (key == "LIST" && size >= 4 && b.ascii(pos, 4) == "INFO") {
                var p = pos + 4
                while (p + 8 <= pos + size) {
                    val tag = b.ascii(p, 4); val n = b.le32(p + 4); p += 8
                    if (n < 0 || n > pos + size - p) break
                    t.set(tag, b.copyOfRange(p, p + n).toString(Charsets.UTF_8)); p += n + (n and 1)
                }
            }
            pos += size.toInt() + (size.toInt() and 1)
        }
        return t
    }
    private fun apeHeader(b: ByteArray): Tags {
        val t = Tags(); val version = b.le16(4)
        var frames = 0L; var finalBlocks = 0L; var blocks = 0L
        if (version >= 3980) {
            val offset = b.le32(8)
            if (offset >= 52 && offset <= b.size - 24) {
                blocks = b.le32(offset + 4).toLong() and 0xffffffffL
                finalBlocks = b.le32(offset + 8).toLong() and 0xffffffffL
                frames = b.le32(offset + 12).toLong() and 0xffffffffL
                t.bits = b.le16(offset + 16); t.rate = b.le32(offset + 20)
            }
        } else if (version in 3800..3970 && b.size >= 32) {
            val flags = b.le16(8); val compression = b.le16(6)
            t.bits = if (flags and 1 != 0) 8 else if (flags and 8 != 0) 24 else 16
            t.rate = b.le32(12); frames = b.le32(24).toLong() and 0xffffffffL
            finalBlocks = b.le32(28).toLong() and 0xffffffffL
            blocks = if (version >= 3950) 73728L * 4 else if (version >= 3900 || compression >= 4000) 73728 else 9216
        }
        if (t.rate in 1..768000 && frames in 1..1_000_000 && blocks in 1..4_194_304 && finalBlocks in 1..blocks) {
            t.duration = ((frames - 1) * blocks + finalBlocks) * 1000 / t.rate
        }
        return t
    }
    private fun ape(b: ByteArray): Tags {
        val t = Tags(); if (b.size < 32) return t
        val footer = b.size - 32; val count = b.le32(footer + 16).coerceIn(0, 1000)
        var pos = if (b.ascii(0, 8) == "APETAGEX") 32 else 0
        repeat(count) {
            if (pos + 8 <= footer) {
                val n = b.le32(pos); val flags = b.le32(pos + 4); pos += 8
                val z = b.indexOfZero(pos)
                if (z < pos || z >= footer || n < 0 || n > footer - z - 1) { pos = footer; return@repeat }
                val key = b.ascii(pos, z - pos); pos = z + 1
                if (flags and 6 == 0) t.set(key, b.copyOfRange(pos, pos + n).toString(Charsets.UTF_8))
                else if (key.equals("Cover Art (Front)", true)) {
                    val start = b.indexOfZero(pos) + 1
                    if (start > pos && start < pos + n && pos + n - start <= MAX_ART) t.art = b.copyOfRange(start, pos + n)
                }
                pos += n
            }
        }
        return t
    }
    const val MAX_TAG = 16 * 1024 * 1024
    const val MAX_ART = 8 * 1024 * 1024
}
private fun Byte.u(): Int = toInt() and 255
private fun ByteArray.ascii(start: Int, count: Int): String = if (start >= 0 && count >= 0 && start <= size - count) String(this, start, count, Charsets.ISO_8859_1) else ""
private fun ByteArray.be(start: Int, count: Int): Int { if (start < 0 || count !in 1..4 || start > size - count) return 0; var n = 0; repeat(count) { n = (n shl 8) or this[start + it].u() }; return n }
private fun ByteArray.le32(start: Int): Int { if (start < 0 || start > size - 4) return 0; return this[start].u() or (this[start + 1].u() shl 8) or (this[start + 2].u() shl 16) or (this[start + 3].u() shl 24) }
private fun ByteArray.le16(start: Int): Int = if (start >= 0 && start <= size - 2) this[start].u() or (this[start + 1].u() shl 8) else 0
private fun ByteArray.indexOfZero(start: Int): Int { for (i in start.coerceAtLeast(0) until size) if (this[i] == 0.toByte()) return i; return -1 }
private fun syncSafe(b: ByteArray, pos: Int): Int = if (pos >= 0 && pos <= b.size - 4) ((b[pos].u() and 127) shl 21) or ((b[pos + 1].u() and 127) shl 14) or ((b[pos + 2].u() and 127) shl 7) or (b[pos + 3].u() and 127) else 0
