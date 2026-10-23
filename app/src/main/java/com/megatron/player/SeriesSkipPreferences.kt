package com.megatron.player

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

data class SeriesSkipKey(val machineIdentifier: String, val showKey: String) {
    init { require(machineIdentifier.isNotBlank() && showKey.isNotBlank()) }
    val storageKey: String get() = MessageDigest.getInstance("SHA-256")
        .digest("${machineIdentifier.length}:$machineIdentifier${showKey.length}:$showKey".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
data class EpisodeSkipTimes(val introSeconds: Int, val outroSeconds: Int) {
    init { require(introSeconds in 0..3600 && outroSeconds in 0..3600) }
}
fun resolveEpisodeSkipTimes(global: EpisodeSkipTimes, override: EpisodeSkipTimes?): EpisodeSkipTimes = override ?: global

class SeriesSkipPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("series_skip_v1", Context.MODE_PRIVATE)
    private val global = PlaybackPreferencesStore(context)
    fun global(): EpisodeSkipTimes = global.read().let { EpisodeSkipTimes(it.episodeIntroSeconds, it.episodeOutroSeconds) }
    fun override(key: SeriesSkipKey): EpisodeSkipTimes? = prefs.getString(key.storageKey, null)?.let { raw ->
        runCatching { JSONObject(raw).let { EpisodeSkipTimes(it.getInt("intro"), it.getInt("outro")) } }.getOrNull()
    }
    fun resolve(key: SeriesSkipKey?): EpisodeSkipTimes = resolveEpisodeSkipTimes(global(), key?.let(::override))
    fun save(key: SeriesSkipKey, value: EpisodeSkipTimes?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key.storageKey)
        else edit.putString(key.storageKey, JSONObject().put("intro", value.introSeconds).put("outro", value.outroSeconds).toString())
        edit.apply()
    }
}
