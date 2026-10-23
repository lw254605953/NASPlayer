package com.megatron.player.plex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PlexFavorite(val serverId: String, val machineIdentifier: String, val serverName: String, val movie: PlexMovie) {
    val key: String get() = "$machineIdentifier|${movie.ratingKey}"
    fun belongsTo(config: PlexServerConfig): Boolean = machineIdentifier.isNotBlank() && machineIdentifier == config.machineIdentifier
}

/** Local title bookmarks, independent of watched status and Plex's account watchlist. */
class PlexFavoritesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plex_favorites_v1", Context.MODE_PRIVATE)

    fun list(): List<PlexFavorite> {
        val array = JSONArray(preferences.getString("items", "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val movie = PlexMovieCodec.fromJson(item.optJSONObject("movie")) ?: return@mapNotNull null
            if (movie.type !in setOf("movie", "show")) return@mapNotNull null
            val server = item.optString("server").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlexFavorite(server, item.optString("machine"), item.optString("name"), movie)
        }.distinctBy { it.key }
    }

    fun contains(config: PlexServerConfig, ratingKey: String): Boolean = list().any { it.belongsTo(config) && it.movie.ratingKey == ratingKey }

    fun toggle(config: PlexServerConfig, movie: PlexMovie): Boolean {
        require(movie.type in setOf("movie", "show"))
        val old = list()
        val existing = old.firstOrNull { it.belongsTo(config) && it.movie.ratingKey == movie.ratingKey }
        if (existing != null) { remove(existing); return false }
        val snapshot = PlexMovie(movie.ratingKey, movie.title, year = movie.year, thumb = movie.thumb, rating = movie.rating, type = movie.type)
        write(old + PlexFavorite(config.id, config.machineIdentifier, config.name, snapshot))
        return true
    }

    fun remove(item: PlexFavorite) = write(list().filterNot { it.key == item.key })

    private fun write(items: List<PlexFavorite>) {
        val array = JSONArray(items.map { item -> JSONObject().put("server", item.serverId)
            .put("machine", item.machineIdentifier).put("name", item.serverName).put("movie", PlexMovieCodec.toJson(item.movie)) })
        check(preferences.edit().putString("items", array.toString()).commit()) { "收藏保存失败，请重试" }
    }
}
