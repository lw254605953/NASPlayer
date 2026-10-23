package com.megatron.player.source

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedLocation(val sourceId: String, val path: String, val name: String, val isDirectory: Boolean, val documentUri: String? = null)

class SourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("media_sources_v1", Context.MODE_PRIVATE)
    private val secrets = DeviceSecrets("MegatronPlayer.SourceSecrets.v1")

    private fun records(): List<JSONObject> {
        val array = JSONArray(prefs.getString("connections", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun config(item: JSONObject) = MediaSourceConfig(item.getString("id"), item.getString("name"),
        SourceKind.valueOf(item.getString("kind")), item.getString("address"), item.optString("username"))
    fun list(): List<MediaSourceConfig> = records().map(::config)
    fun find(id: String): MediaSourceConfig? = list().firstOrNull { it.id == id }
    fun credentials(id: String): SourceCredentials? = records().firstOrNull { it.getString("id") == id }?.let {
        SourceCredentials(config(it), it.optString("secret").takeIf(String::isNotEmpty)?.let(secrets::decrypt) ?: "")
    }
    fun save(source: MediaSourceConfig, password: String?) {
        val old = records()
        val item = JSONObject().apply {
            put("id", source.id); put("name", source.name); put("kind", source.kind.name)
            put("address", source.address); put("username", source.username)
            put("secret", password?.let(secrets::encrypt) ?: old.firstOrNull { it.getString("id") == source.id }?.optString("secret") ?: "")
        }
        prefs.edit().putString("connections", JSONArray(old.filterNot { it.getString("id") == source.id } + item).toString()).apply()
    }
    fun remove(id: String) {
        prefs.edit().putString("connections", JSONArray(records().filterNot { it.getString("id") == id }).toString()).apply()
        writeFavorites(favorites().filterNot { it.sourceId == id })
    }
    fun favorites(): List<SavedLocation> {
        val a = JSONArray(prefs.getString("favorites", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            SavedLocation(it.getString("source"), it.getString("path"), it.getString("name"), it.getBoolean("directory"),
                if (it.has("uri") && !it.isNull("uri")) it.getString("uri") else null)
        }
    }
    fun toggleFavorite(item: SavedLocation) {
        val old = favorites()
        writeFavorites(if (old.any { it.sourceId == item.sourceId && it.path == item.path })
            old.filterNot { it.sourceId == item.sourceId && it.path == item.path } else old + item)
    }
    private fun writeFavorites(items: List<SavedLocation>) {
        prefs.edit().putString("favorites", JSONArray(items.map { item -> JSONObject().apply {
            put("source", item.sourceId); put("path", item.path); put("name", item.name)
            put("directory", item.isDirectory); put("uri", item.documentUri ?: JSONObject.NULL)
        } }).toString()).apply()
    }
}
