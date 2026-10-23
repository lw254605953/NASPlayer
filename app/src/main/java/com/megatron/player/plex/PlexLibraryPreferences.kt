package com.megatron.player.plex

import android.content.Context
import org.json.JSONArray

class PlexLibraryPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("plex_library_ui_v1", Context.MODE_PRIVATE)
    init {
        if (prefs.getInt("view_policy", 0) < 1) {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.endsWith(".view") }.forEach { edit.putString(it, PlexViewMode.POSTER_FOUR.name) }
            edit.putInt("view_policy", 1).apply()
        }
        if (prefs.getInt("sort_policy", 0) < 2) {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.endsWith(".sort") }.forEach { sortKey ->
                edit.putString(sortKey, PlexSort.RELEASED.name).putBoolean(sortKey.removeSuffix(".sort") + ".descending", true)
            }
            edit.putInt("sort_policy", 2).apply()
        }
    }
    private fun key(serverId: String, libraryKey: String) = "$serverId:$libraryKey"

    fun category(): String = if (prefs.getString("library_category", "movie") == "show") "show" else "movie"
    fun saveCategory(category: String) { prefs.edit().putString("library_category", category).apply() }

    fun options(serverId: String, libraryKey: String): PlexLibraryOptions {
        val scope = key(serverId, libraryKey)
        val sort = PlexSort.entries.firstOrNull { it.name == prefs.getString("$scope.sort", null) } ?: PlexSort.RELEASED
        val view = PlexViewMode.entries.firstOrNull { it.name == prefs.getString("$scope.view", null) } ?: PlexViewMode.POSTER_FOUR
        return PlexLibraryOptions(sort, prefs.getBoolean("$scope.descending", sort.defaultDescending), view)
    }

    fun saveOptions(serverId: String, libraryKey: String, options: PlexLibraryOptions) {
        val scope = key(serverId, libraryKey)
        prefs.edit().putString("$scope.sort", options.sort.name).putBoolean("$scope.descending", options.descending)
            .putString("$scope.view", options.view.name).apply()
    }

    fun history(serverId: String, libraryKey: String): List<String> {
        val items = runCatching { JSONArray(prefs.getString("${key(serverId, libraryKey)}.history", "[]")) }.getOrElse { JSONArray() }
        return (0 until items.length()).mapNotNull { items.optString(it).takeIf(String::isNotBlank) }.take(12)
    }

    fun recordSearch(serverId: String, libraryKey: String, query: String) {
        writeHistory(serverId, libraryKey, updatedSearchHistory(history(serverId, libraryKey), query))
    }

    fun removeSearch(serverId: String, libraryKey: String, query: String) {
        writeHistory(serverId, libraryKey, history(serverId, libraryKey).filterNot { it.equals(query, ignoreCase = true) })
    }

    fun clearHistory(serverId: String, libraryKey: String) { prefs.edit().remove("${key(serverId, libraryKey)}.history").apply() }

    private fun writeHistory(serverId: String, libraryKey: String, values: List<String>) {
        prefs.edit().putString("${key(serverId, libraryKey)}.history", JSONArray(values).toString()).apply()
    }
}
