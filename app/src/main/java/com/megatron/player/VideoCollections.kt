package com.megatron.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.megatron.player.plex.*
import com.megatron.player.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VideoTimePoint(val id: String, val name: String, val video: RecentVideo, val plexMachine: String = "")
data class VideoFileFavorite(val video: RecentVideo, val plexMachine: String = "")

/** Explicit bookmarks are independent from history and never apply the completed-video resume rule. */
class VideoCollections(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("video_collections_v1", Context.MODE_PRIVATE)
    private val sourceStore = SourceStore(context)
    private val plexStore = PlexStore(context)
    private val titles = PlexFavoritesStore(context)

    fun points(): List<VideoTimePoint> = read("points").mapNotNull { item ->
        decode(item)?.let { VideoTimePoint(item.optString("id"), item.optString("name", it.title), it, item.optString("machine")) }
    }.filter { it.id.isNotBlank() }
    fun files(): List<VideoFileFavorite> = read("files").mapNotNull { item -> decode(item)?.let { VideoFileFavorite(it, item.optString("machine")) } }
    fun machine(uri: String): String = PlexUris.parse(Uri.parse(uri))?.let { ref -> plexStore.config()?.takeIf { it.id == ref.serverId }?.machineIdentifier }.orEmpty()
    fun pointsFor(uri: String): List<VideoTimePoint> = points().filter { it.video.uri == uri && it.plexMachine == machine(uri) }
    fun addPoint(video: RecentVideo, name: String): VideoTimePoint {
        val old = points()
        val machine = machine(video.uri)
        old.firstOrNull { it.video.uri == video.uri && it.video.positionMs == video.positionMs && it.plexMachine == machine }?.let { return it }
        val point = VideoTimePoint(UUID.randomUUID().toString(), name.trim().take(160).ifBlank { "${video.title} · ${formatPlaybackTime(video.positionMs)}" }, video, machine)
        write("points", (old + point).map { encode(it.video, it.plexMachine).put("id", it.id).put("name", it.name) })
        return point
    }
    fun rename(point: VideoTimePoint, name: String) {
        require(name.isNotBlank()) { "名称不能为空" }
        write("points", points().map { if (it.id == point.id) it.copy(name = name.trim().take(160)) else it }
            .map { encode(it.video, it.plexMachine).put("id", it.id).put("name", it.name) })
    }
    fun remove(point: VideoTimePoint) = write("points", points().filterNot { it.id == point.id }
        .map { encode(it.video, it.plexMachine).put("id", it.id).put("name", it.name) })
    fun remove(file: VideoFileFavorite) = write("files", files().filterNot { it.video.uri == file.video.uri && it.plexMachine == file.plexMachine }.map { encode(it.video, it.plexMachine) })

    fun unavailable(video: RecentVideo, machine: String): String? {
        SourceUris.parse(Uri.parse(video.uri))?.let { if (sourceStore.find(it.first) == null) return "片源已移除，请重新添加片源；也可以删除这条收藏。" }
        PlexUris.parse(Uri.parse(video.uri))?.let { ref ->
            val config = plexStore.config()
            if (config?.id != ref.serverId || (machine.isNotBlank() && config.machineIdentifier != machine))
                return "这条收藏的 Plex 片源未连接，请到设置连接原片源；也可以删除这条收藏。"
        }
        if (Uri.parse(video.uri).scheme == "content") {
            // Document providers may revoke access; the player reports a readable error if opening fails.
            return null
        }
        return null
    }

    private fun location(uri: String, title: String): SavedLocation? {
        SourceUris.parse(Uri.parse(uri))?.let { return SavedLocation(it.first, it.second, title, false) }
        sourceStore.favorites().firstOrNull { !it.isDirectory && it.documentUri == uri }?.let { return it }
        val document = Uri.parse(uri)
        if (document.scheme == "content") sourceStore.list().firstOrNull { config ->
            config.kind == SourceKind.LOCAL && runCatching {
                val tree = Uri.parse(config.address)
                tree.authority == document.authority && DocumentsContract.getTreeDocumentId(tree) == DocumentsContract.getTreeDocumentId(document)
            }.getOrDefault(false)
        }?.let { return SavedLocation(it.id, DocumentsContract.getDocumentId(document), title, false, uri) }
        return null
    }
    fun isFavorite(uri: String): Boolean {
        location(uri, "")?.let { file -> if (sourceStore.favorites().any { it.sourceId == file.sourceId && it.path == file.path }) return true }
        PlexUris.parse(Uri.parse(uri))?.let { ref -> plexStore.config()?.takeIf { it.id == ref.serverId }?.let { if (titles.contains(it, ref.ratingKey)) return true } }
        return files().any { it.video.uri == uri && it.plexMachine == machine(uri) }
    }
    suspend fun toggleFavorite(video: RecentVideo): Boolean {
        val uri = video.uri
        location(uri, video.title)?.let { sourceStore.toggleFavorite(it); return isFavorite(uri) }
        val generic = files().firstOrNull { it.video.uri == uri && it.plexMachine == machine(uri) }
        if (generic != null) { remove(generic); return false }
        PlexUris.parse(Uri.parse(uri))?.let { ref ->
            val config = plexStore.config()?.takeIf { it.id == ref.serverId } ?: error("Plex 连接已移除")
            val old = titles.list().firstOrNull { it.belongsTo(config) && it.movie.ratingKey == ref.ratingKey }
            if (old != null) { titles.remove(old); return false }
            val credentials = plexStore.credentials(config.id) ?: error("Plex 连接已移除")
            val movie = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).detail(credentials, ref.ratingKey) }
            check(plexStore.config()?.let { it.id == config.id && it.machineIdentifier == config.machineIdentifier } == true) { "Plex 连接已变更，请重试" }
            if (movie.type in setOf("movie", "show")) return titles.toggle(config, movie)
        }
        write("files", (files() + VideoFileFavorite(video.copy(positionMs = 0), machine(uri))).map { encode(it.video, it.plexMachine) })
        return true
    }

    private fun read(key: String): List<JSONObject> {
        val a = JSONArray(prefs.getString(key, "[]"))
        return (0 until a.length()).mapNotNull(a::optJSONObject)
    }
    private fun write(key: String, items: List<JSONObject>) {
        check(prefs.edit().putString(key, JSONArray(items).toString()).commit()) { "收藏保存失败，请重试" }
    }
    private fun encode(v: RecentVideo, machine: String) = JSONObject().put("uri", v.uri).put("title", v.title)
        .put("position", v.positionMs).put("duration", v.durationMs).put("subtitle", v.subtitleUri ?: JSONObject.NULL)
        .put("subtitleName", v.subtitleName ?: JSONObject.NULL).put("machine", machine)
    private fun decode(o: JSONObject): RecentVideo? {
        val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return null
        return RecentVideo(uri, o.optString("title", "视频"), o.optLong("position").coerceAtLeast(0), o.optLong("duration").coerceAtLeast(0),
            if (o.isNull("subtitle")) null else o.optString("subtitle").takeIf { it.isNotBlank() },
            if (o.isNull("subtitleName")) null else o.optString("subtitleName").takeIf { it.isNotBlank() })
    }
}
