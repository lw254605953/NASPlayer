package com.megatron.player.music

import android.content.Context
import com.megatron.player.source.*
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Optional NAS lyrics use the existing authenticated bounded reads, never an online matching service. */
internal class MusicLyricsReader(context: Context) {
    private val contextApp = context.applicationContext
    private val sources = SourceStore(context)
    private val tags = MusicTagReader(context, sources)
    private val folder = File(context.cacheDir, "music_lyrics_v1").apply { mkdirs() }
    @Volatile private var call: okhttp3.Call? = null
    fun cancel() { call?.cancel(); tags.cancel() }
    suspend fun load(track: MusicTrack): SongLyrics? = withContext(Dispatchers.IO) {
        com.megatron.player.music.metadata.MusicSupplementStore(contextApp).lyrics(track)?.let { return@withContext MusicLyricsParser.parse(it) }
        val credentials = runCatching { sources.credentials(track.sourceId) }.getOrNull() ?: return@withContext null
        val identity = "${track.id}|${track.sizeBytes}|${track.modifiedAt}|${credentials.config.address}|${credentials.config.username}"
        val cache = File(folder, musicId(track.sourceId, identity) + ".json")
        val cached = runCatching {
            if (cache.length() > MusicLyricsParser.MAX_BYTES * 6L || !cache.isFile) return@runCatching null
            val value = JSONObject(cache.readText())
            val ttl = if (value.optString("text").isBlank()) 120_000L else 600_000L
            value.takeIf { System.currentTimeMillis() - it.getLong("at") in 0..ttl }
        }.getOrNull()
        if (cached != null) return@withContext MusicLyricsParser.parse(cached.optString("text"))
        try {
            currentCoroutineContext().ensureActive()
            val parent = track.path.substringBeforeLast('/', "")
            val name = track.path.substringAfterLast('/').substringBeforeLast('.') + ".lrc"
            val entry = WebDavClient().list(credentials, parent) { call = it }
                .firstOrNull { !it.isDirectory && it.name.equals(name, true) && it.size in 1..MusicLyricsParser.MAX_BYTES.toLong() }
            var text = entry?.let {
                val bytes = tags.range(track.copy(path = it.path, sizeBytes = it.size), 0, it.size.toInt()) { request -> call = request }
                check(bytes.size == it.size.toInt())
                MusicLyricsParser.decode(bytes)
            }.orEmpty()
            if (MusicLyricsParser.parse(text) == null) text = MusicTagParser.read(track.format, track.sizeBytes) { start, count ->
                tags.range(track, start, count) { call = it }
            }.lyrics
            currentCoroutineContext().ensureActive()
            val result = MusicLyricsParser.parse(text)
            val tmp = File(folder, cache.name + UUID.randomUUID() + ".tmp")
            try {
                tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("text", if (result != null) text else "").toString())
                if (!tmp.renameTo(cache)) tmp.delete()
                var total = folder.listFiles()?.sumOf { it.length() } ?: 0
                if (total > 16 * 1024 * 1024) folder.listFiles()?.sortedBy { it.lastModified() }?.forEach {
                    if (total > 16 * 1024 * 1024 && it != cache) { val size = it.length(); if (it.delete()) total -= size }
                }
            } finally { tmp.delete() }
            result
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
        finally { call = null }
    }
}
