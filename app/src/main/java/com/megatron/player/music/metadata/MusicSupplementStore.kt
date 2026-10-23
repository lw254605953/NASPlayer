package com.megatron.player.music.metadata

import android.content.Context
import com.megatron.player.music.*
import org.json.JSONObject

/** Only confirmed display overrides. Original index and media bytes remain authoritative. */
class MusicSupplementStore(context: Context) {
    private val artRoot = java.io.File(context.filesDir, "music_art").canonicalFile
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun record(id: String): JSONObject? = synchronized(lock) { prefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() } }
    fun has(id: String) = prefs.contains(id)
    fun recordFor(track: MusicTrack): JSONObject? = record(track.id)?.takeIf { it.optLong("size") == track.sizeBytes && it.optLong("modified") == track.modifiedAt }
    fun hasValid(track: MusicTrack) = recordFor(track) != null
    fun decorate(track: MusicTrack): MusicTrack {
        val row = recordFor(track) ?: return track
        val art = row.optString("artPath").takeIf { runCatching { val file = java.io.File(it).canonicalFile; file.parentFile == artRoot && file.isFile }.getOrDefault(false) }
        return track.copy(title = row.optString("title").ifBlank { track.title }, artist = row.optString("artist").ifBlank { track.artist },
            album = row.optString("album").ifBlank { track.album }, artworkPath = art ?: track.artworkPath)
    }
    fun lyrics(track: MusicTrack): String? = recordFor(track)
        ?.optString("lyrics")?.takeIf(String::isNotBlank)
    fun put(track: MusicTrack, row: JSONObject) = synchronized(lock) {
        val value = JSONObject(row.toString()).put("size", track.sizeBytes).put("modified", track.modifiedAt).put("at", System.currentTimeMillis()).toString()
        require(value.toByteArray().size <= 96 * 1024) { "单曲资料超过容量上限" }
        require(prefs.contains(track.id) || prefs.all.size < 10_000) { "补充资料已达一万首上限" }
        val total = prefs.all.filterKeys { it != track.id }.values.sumOf { (it as? String)?.toByteArray()?.size?.toLong() ?: 0L }
        require(total + value.toByteArray().size <= 12L * 1024 * 1024) { "补充资料已达 12 MB 容量上限" }
        check(prefs.edit().putString(track.id, value).commit()) { "资料保存失败" }
    }
    fun remove(id: String) = synchronized(lock) { check(prefs.edit().remove(id).commit()) }
    fun move(old: MusicTrack, new: MusicTrack?) = synchronized(lock) {
        val value = prefs.getString(old.id, null) ?: return@synchronized
        val edit = prefs.edit().remove(old.id)
        if (new != null) edit.putString(new.id, value)
        check(edit.commit())
    }
    companion object {
        const val PREFS = "music_supplements_v1"
        private val lock = Any()
        fun validateBackup(values: Map<String, *>) {
            require(values.size <= 10_000)
            var size = 0L
            for ((key, value) in values) {
                require(key.matches(Regex("[0-9a-f]{64}")) && value is String)
                size += value.toByteArray().size
                require(value.toByteArray().size <= 96 * 1024 && size <= 12L * 1024 * 1024)
                val row = JSONObject(value)
                for (field in listOf("title", "artist", "album")) require(row.optString(field).length <= 512)
                require(row.optString("lyrics").length <= 32_768)
                // Restored local image paths must never grant access to arbitrary files on this device.
                row.remove("artPath")
            }
        }
    }
}

internal fun placeholderMusic(value: String): Boolean = value.isBlank() || value.trim().lowercase() in setOf("unknown", "unknown artist", "unknown album", "未知", "未知歌手", "未知艺术家", "未知专辑")
internal fun missingMusicTitle(track: MusicTrack) = placeholderMusic(track.title) || track.title == track.path.substringAfterLast('/').substringBeforeLast('.') || track.title.matches(Regex("(?i)(track|audio|歌曲|音轨)?[ _-]*\\d+"))
