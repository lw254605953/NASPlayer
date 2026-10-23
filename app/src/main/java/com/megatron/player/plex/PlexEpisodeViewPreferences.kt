package com.megatron.player.plex

import android.content.Context

enum class PlexEpisodeViewMode(val label: String) {
    LIST("列表"),
    POSTER_TWO("每行2集"),
    POSTER_FOUR("每行4集"),
    NUMBERS("仅集数")
}

class PlexEpisodeViewPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plex_episode_view_v1", Context.MODE_PRIVATE)
    init {
        if (preferences.getInt("view_policy", 0) < 1) {
            val edit = preferences.edit()
            preferences.all.keys.filter { it.contains("::") }.forEach { edit.putString(it, PlexEpisodeViewMode.POSTER_FOUR.name) }
            edit.putInt("view_policy", 1).apply()
        }
    }

    fun get(serverId: String, showKey: String): PlexEpisodeViewMode {
        val value = preferences.getString(key(serverId, showKey), PlexEpisodeViewMode.POSTER_FOUR.name) ?: PlexEpisodeViewMode.POSTER_FOUR.name
        return runCatching { PlexEpisodeViewMode.valueOf(value) }.getOrDefault(PlexEpisodeViewMode.POSTER_FOUR)
    }

    fun save(serverId: String, showKey: String, mode: PlexEpisodeViewMode) {
        preferences.edit().putString(key(serverId, showKey), mode.name).apply()
    }

    private fun key(serverId: String, showKey: String) = "${serverId.trim()}::${showKey.trim()}"
}
