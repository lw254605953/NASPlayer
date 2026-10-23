package com.megatron.player.music

import android.content.Context
import com.megatron.player.source.SourceStore
import com.megatron.player.source.WebDavHttp
import com.megatron.player.source.WebDavClient
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Headers
import java.io.File

/** Reads bounded tag blocks only. It never hands a remote URL to an unbounded metadata retriever. */
internal class MusicTagReader(context: Context, private val sources: SourceStore) {
    private val artwork = MusicArtworkStore(context)
    @Volatile private var call: okhttp3.Call? = null
    private val folderArt = ConcurrentHashMap<String, String>()
    fun cancel() { call?.cancel() }
    fun beginScan() { folderArt.clear() }
    fun cacheArtwork(bytes: ByteArray): String? = artwork.save(bytes)

    fun read(track: MusicTrack, directoryEntries: List<com.megatron.player.source.BrowserEntry>? = null, onCall: (okhttp3.Call) -> Unit = {}): MusicTrack {
        var result = track
        try {
            val tags = MusicTagParser.read(track.format, track.sizeBytes) { start, count -> range(track, start, count, onCall) }
            var coverFailed = false
            val art = tags.art?.takeIf { it.size <= MAX_ART }?.let { bytes ->
                artwork.save(bytes)
            } ?: track.artworkPath?.takeIf { File(it).isFile } ?: runCatching { folderCover(track, directoryEntries, onCall) }.onFailure { coverFailed = true }.getOrNull()
            result = track.copy(title = tags.title.ifBlank { track.title }, artist = tags.artist.ifBlank { track.artist },
                album = tags.album.ifBlank { track.album }, durationMs = tags.duration.takeIf { it > 0 } ?: track.durationMs,
                sampleRate = tags.rate.takeIf { it > 0 } ?: track.sampleRate, bitDepth = tags.bits.takeIf { it > 0 } ?: track.bitDepth,
                trackNumber = tags.track.takeIf { it > 0 } ?: track.trackNumber, discNumber = tags.disc.takeIf { it > 0 } ?: track.discNumber, artworkPath = art,
                indexedAt = if (coverFailed) 0 else System.currentTimeMillis(),
                metadataVersion = if (coverFailed) 0 else MUSIC_METADATA_VERSION)
        } catch (_: Exception) {
            // A corrupt/oversized tag or unsupported optional field must not hide a playable file.
            result = track.copy(indexedAt = 0, metadataVersion = 0)
        } finally { call = null }
        return result
    }

    private fun folderCover(track: MusicTrack, directoryEntries: List<com.megatron.player.source.BrowserEntry>?, onCall: (okhttp3.Call) -> Unit): String? {
        val parent = track.path.substringBeforeLast('/', "")
        val key = musicId(track.sourceId, parent)
        folderArt[key]?.let { if (it.isEmpty()) return null; if (File(it).isFile) return it }
        val credentials = sources.credentials(track.sourceId) ?: return null
        val candidates = listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png")
        val entry = (directoryEntries ?: WebDavClient().list(credentials, parent) { call = it; onCall(it) })
            .filter { !it.isDirectory && it.size in 1..MAX_ART.toLong() && it.name.lowercase() in candidates }
            .minByOrNull { candidates.indexOf(it.name.lowercase()) }
        if (entry == null) { folderArt[key] = ""; return null }
        val imageTrack = track.copy(path = entry.path, sizeBytes = entry.size)
        val bytes = range(imageTrack, 0, entry.size.toInt(), onCall)
        check(bytes.size.toLong() == entry.size) { "歌曲封面不完整" }
        return artwork.save(bytes).also { folderArt[key] = it.orEmpty() }
    }

    internal fun range(track: MusicTrack, start: Long, count: Int, onCall: (okhttp3.Call) -> Unit): ByteArray {
        require(count in 1..MAX_TAG && start >= 0)
        val credentials = sources.credentials(track.sourceId) ?: error("音乐来源已删除")
        val base = WebDavHttp.validateBase(credentials.config.address)
        val url = WebDavHttp.urlForPath(base, WebDavHttp.cleanRelativePath(track.path))
        val length = if (track.sizeBytes > 0) minOf(count.toLong(), track.sizeBytes - start).toInt() else count
        require(length > 0)
        WebDavHttp.execute(credentials, url, "GET", null, Headers.headersOf("Range", "bytes=$start-${start + length - 1}", "Accept-Encoding", "identity")) {
            call = it; onCall(it)
        }.use { response ->
            if (!response.isSuccessful) throw WebDavHttp.httpFailure(response.code)
            if (start > 0 && response.code != 206) error("NAS 不支持范围读取")
            if (response.code == 206) {
                val actual = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                require(actual == start)
            }
            val input = response.body?.byteStream() ?: error("音乐标签为空")
            val data = ByteArray(length)
            var read = 0
            while (read < data.size) { val n = input.read(data, read, data.size - read); if (n < 0) break; read += n }
            return if (read == length) data else data.copyOf(read)
        }
    }

    companion object { const val MAX_TAG = MusicTagParser.MAX_TAG; const val MAX_ART = MusicTagParser.MAX_ART }
}
