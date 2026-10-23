package com.megatron.player.music

import android.content.Context
import com.megatron.player.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class MusicVideoCategory(val label: String, val preferenceKey: String) {
    MV("MV", "mv_roots"), CONCERT("演唱会", "concert_roots");
    companion object { fun fromLabel(label: String) = entries.firstOrNull { it.label == label } }
}
internal fun encodeMusicVideoRoots(roots: List<MusicRoot>): String = JSONArray(
    roots.map { it.copy(path = it.path.trim('/')) }.distinctBy { it.sourceId to it.path }.map {
        JSONObject().put("source", it.sourceId).put("path", it.path).put("name", it.name)
    }).toString()
internal fun decodeMusicVideoRoots(raw: String?): List<MusicRoot> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).map { array.getJSONObject(it) }.map {
        MusicRoot(it.getString("source"), it.getString("path"), it.getString("name"))
    }
}.getOrDefault(emptyList())

/** Directory configuration only. Video files never enter the song index or queue. */
class MusicVideoRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("music_library_v1", Context.MODE_PRIVATE)
    private val sources = SourceStore(context.applicationContext)
    private val webDav = WebDavClient()
    private val _roots = MutableStateFlow(MusicVideoCategory.entries.associateWith { decodeMusicVideoRoots(prefs.getString(it.preferenceKey, null)) })
    val roots: StateFlow<Map<MusicVideoCategory, List<MusicRoot>>> = _roots
    fun save(category: MusicVideoCategory, roots: List<MusicRoot>) {
        val raw = encodeMusicVideoRoots(roots)
        prefs.edit().putString(category.preferenceKey, raw).apply()
        _roots.value = _roots.value + (category to decodeMusicVideoRoots(raw))
    }
    fun invalidateDirectory(sourceId: String, path: String) { runCatching { sources.credentials(sourceId)?.let { DirectoryBrowserCache.invalidate(it, path) } } }
    suspend fun listDirectory(sourceId: String, path: String) = withContext(Dispatchers.IO) {
        val credentials = sources.credentials(sourceId)?.takeIf { it.config.kind == SourceKind.WEBDAV }
            ?: throw IOException("视频连接已移除，请重新选择目录")
        DirectoryBrowserCache.list(credentials, path)
    }
}
