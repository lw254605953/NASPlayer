package com.megatron.player.plex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.megatron.player.source.MediaSourceConfig

data class PlexPathMapping(val plexRoot: String, val webDavRoot: String)
data class PlexWebDavBinding(val sourceId: String, val mappings: List<PlexPathMapping> = emptyList(),
    val autoMatch: Boolean = false, val mediaRoot: String = "")

/** References an existing separately encrypted WebDAV connection, never duplicates its credentials. */
class PlexWebDavBindingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("plex_webdav_bindings_v1", Context.MODE_PRIVATE)

    fun read(serverId: String): PlexWebDavBinding? {
        val raw = prefs.getString(serverId, null) ?: return null
        return try {
            val item = JSONObject(raw)
            val array = item.getJSONArray("mappings")
            PlexWebDavBinding(item.getString("sourceId"), (0 until array.length()).map { index ->
                val mapping = array.getJSONObject(index)
                PlexPathMapping(mapping.getString("plexRoot"), mapping.getString("webDavRoot"))
            }, item.optBoolean("autoMatch", false), item.optString("mediaRoot", ""))
        } catch (error: Exception) {
            // A saved binding must not silently become a Plex video request when corrupted.
            throw PlexPlaybackFailure("WebDAV 绑定配置无法读取，请在设置中重新保存", cause = error)
        }
    }

    fun save(serverId: String, binding: PlexWebDavBinding) {
        require(serverId.isNotBlank() && binding.sourceId.isNotBlank()) { "请选择有效的服务器与 WebDAV 连接" }
        val mappings = if (binding.autoMatch && binding.mappings.isEmpty()) emptyList() else PlexWebDavPaths.validateMappings(binding.mappings)
        val item = JSONObject().put("sourceId", binding.sourceId).put("autoMatch", binding.autoMatch)
            .put("mediaRoot", PlexWebDavPaths.relativeDirectory(binding.mediaRoot)).put("mappings", JSONArray().apply {
            mappings.forEach { put(JSONObject().put("plexRoot", it.plexRoot).put("webDavRoot", it.webDavRoot)) }
        })
        prefs.edit().putString(serverId, item.toString()).remove("learned:$serverId").apply()
    }

    fun remove(serverId: String) { prefs.edit().remove(serverId).remove("learned:$serverId").apply() }

    @Synchronized fun learned(serverId: String, source: MediaSourceConfig): List<PlexPathMapping> = runCatching {
        val item = JSONObject(prefs.getString("learned:$serverId", null) ?: return emptyList())
        if (item.getString("sourceId") != source.id || item.getString("address") != source.address || item.getString("username") != source.username) return emptyList()
        val list = item.getJSONArray("mappings")
        PlexWebDavPaths.validateMappings((0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            PlexPathMapping(row.getString("plexRoot"), row.getString("webDavRoot"))
        })
    }.getOrDefault(emptyList())

    @Synchronized fun remember(serverId: String, source: MediaSourceConfig, binding: PlexWebDavBinding, mapping: PlexPathMapping) {
        // Do not resurrect rules if a binding changed while a probe was in flight.
        if (read(serverId) != binding) return
        val rows = (listOf(mapping) + learned(serverId, source).filter { it.plexRoot != mapping.plexRoot }).take(32)
        val item = JSONObject().put("sourceId", source.id).put("address", source.address).put("username", source.username)
            .put("mappings", JSONArray().apply { rows.forEach { put(JSONObject().put("plexRoot", it.plexRoot).put("webDavRoot", it.webDavRoot)) } })
        prefs.edit().putString("learned:$serverId", item.toString()).apply()
    }
}

object PlexWebDavPaths {
    fun validateMappings(mappings: List<PlexPathMapping>): List<PlexPathMapping> {
        require(mappings.isNotEmpty()) { "请至少添加一条影片目录对应关系" }
        val normalized = mappings.map { PlexPathMapping(absolutePath(it.plexRoot.trim(), directory = true), relativeDirectory(it.webDavRoot)) }
        require(normalized.map { it.plexRoot }.distinct().size == normalized.size) { "同一个 Plex 目录不能重复对应，请移除重复项" }
        return normalized
    }

    fun relativePath(filePath: String, binding: PlexWebDavBinding): String {
        val file = absolutePath(filePath, directory = false)
        val mapping = validateMappings(binding.mappings).filter {
            file.startsWith(if (it.plexRoot == "/") "/" else "${it.plexRoot}/") && file != it.plexRoot
        }.maxByOrNull { it.plexRoot.length }
            ?: throw PlexPlaybackFailure("影片目录尚未对应，请在设置中配置 Plex 与 WebDAV 目录")
        val suffix = file.removePrefix(mapping.plexRoot).trimStart('/')
        return listOf(mapping.webDavRoot, suffix).filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun absolutePath(value: String, directory: Boolean): String {
        require(value.startsWith('/') && !value.startsWith("//") && !value.any { it == '\\' || it.isISOControl() }) { "Plex 文件目录需为以 / 开头的有效路径" }
        val segments = value.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Plex 文件目录不能包含 . 或 .." }
        require(directory || (segments.isNotEmpty() && !value.endsWith('/'))) { "Plex 未提供有效的视频文件位置" }
        return "/" + segments.joinToString("/")
    }

    fun relativeDirectory(value: String): String {
        val path = value.trim().trim('/')
        require(!path.any { it == '\\' || it.isISOControl() } && path.split('/').none { it == "." || it == ".." }) { "WebDAV 目录不能包含 .、.. 或反斜线" }
        return path.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }
}
