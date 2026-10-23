package com.megatron.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaybackHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_history", Context.MODE_PRIVATE)

    fun list(): List<RecentVideo> {
        val raw = preferences.getString("videos", "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(RecentVideo(
                    uri = uri,
                    title = item.optString("title", "视频"),
                    positionMs = item.optLong("positionMs").coerceAtLeast(0L),
                    durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                    subtitleUri = item.optionalString("subtitleUri"),
                    subtitleName = item.optionalString("subtitleName"),
                    updatedAt = item.optLong("updatedAt"),
                ))
            }
        }.distinctBy { historyIdentity(it.uri) }.take(500)
    }

    fun find(uri: String): RecentVideo? = list().firstOrNull { historyIdentity(it.uri) == historyIdentity(uri) }

    fun save(video: RecentVideo) = write(updatedHistory(list(), video))

    fun remove(uri: String) = write(list().filterNot { historyIdentity(it.uri) == historyIdentity(uri) })

    fun clear() { preferences.edit().remove("videos").apply() }

    private fun write(videos: List<RecentVideo>) {
        val array = JSONArray()
        videos.forEach { video ->
            array.put(JSONObject().apply {
                put("uri", video.uri)
                put("title", video.title)
                put("positionMs", video.positionMs)
                put("durationMs", video.durationMs)
                put("subtitleUri", video.subtitleUri ?: JSONObject.NULL)
                put("subtitleName", video.subtitleName ?: JSONObject.NULL)
                put("updatedAt", video.updatedAt)
            })
        }
        preferences.edit().putString("videos", array.toString()).apply()
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
