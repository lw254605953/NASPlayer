package com.megatron.player.backup

import android.content.Context
import android.content.SharedPreferences
import com.megatron.player.source.DeviceSecrets
import com.megatron.player.source.SourceStore
import com.megatron.player.source.SourceKind
import com.megatron.player.source.WebDavHttp
import com.megatron.player.plex.PlexStore
import org.json.JSONArray
import org.json.JSONObject

/** Explicit configuration allowlist; no cache files, databases or pending server writes are restored. */
internal class BackupSnapshot(private val context: Context) {
    companion object {
        val names = setOf("media_sources_v1", "plex_connection_v1", "plex_webdav_bindings_v1",
            "player_preferences", "playback_preferences_v1", "home_display_v1", "playback_history",
            "video_collections_v1", "plex_favorites_v1", "plex_versions_v1", "audio_sync_v1",
            "subtitle_timing_v1", "plex_library_ui_v1", "plex_episode_view_v1", "music_library_v1",
            "music_queue_v1", "music_playback_v1", "music_ui_v1", "cast_device_v1", "series_skip_v1", "music_supplements_v1")
    }
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun capture(target: BackupTarget): JSONObject {
        val data = JSONObject()
        val sources = SourceStore(context)
        val plex = PlexStore(context)
        names.forEach { name ->
            val values = prefs(name).all.toMutableMap()
            if (name == "media_sources_v1") {
                val rows = JSONArray(values["connections"] as? String ?: "[]")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    row.put("password", sources.credentials(row.getString("id"))?.password ?: error("连接凭据不可用"))
                    row.remove("secret")
                }
                values["connections"] = rows.toString()
            }
            if (name == "plex_connection_v1") {
                values.remove("client")
                if (values.containsKey("token")) values["token"] = plex.credentials()?.token ?: error("Plex凭据不可用")
            }
            if (name == "music_queue_v1") values["items"]?.let { values["items"] = portableQueue(it as String) }
            if (name == "music_supplements_v1") values.keys.toList().forEach { key ->
                values[key] = JSONObject(values[key] as String).apply { remove("artPath") }.toString()
            }
            data.put(name, encode(values))
        }
        return JSONObject().put("app", "MegatronPlayer").put("schema", 3).put("createdAt", System.currentTimeMillis())
            .put("preferences", data).put("backupTarget", JSONObject().put("source", target.sourceId).put("path", target.path))
    }
    private fun portableQueue(raw: String): String {
        val rows = JSONArray(raw)
        for (i in 0 until rows.length()) rows.getJSONObject(i).apply {
            put("art", JSONObject.NULL); put("indexed", 0); put("metadataVersion", 0)
        }
        return rows.toString()
    }
    fun prepare(payload: JSONObject): PreparedBackup {
        require(payload.getString("app") == "MegatronPlayer" && payload.getInt("schema") in 1..3) { "不支持这个备份版本" }
        val data = migrateBackupPreferences(payload.getInt("schema"), payload.getJSONObject("preferences"), names)
        val restored = names.associateWith { decode(data.getJSONObject(it)).toMutableMap() }
        val rows = JSONArray(restored.getValue("media_sources_v1")["connections"] as? String ?: "[]")
        require(rows.length() <= 100) { "备份片源数量超出范围" }
        val ids = HashSet<String>(); val webDavIds = HashSet<String>()
        val sourceSecrets = DeviceSecrets("MegatronPlayer.SourceSecrets.v1")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.getString("id"); require(id.isNotBlank() && id.length <= 200 && ids.add(id)) { "备份片源标识无效" }
            row.getString("name"); row.getString("username")
            val kind = SourceKind.valueOf(row.getString("kind"))
            if (kind == SourceKind.WEBDAV) { WebDavHttp.validateBase(row.getString("address")); webDavIds.add(id) }
            val password = row.getString("password")
            require(password.length <= 16_384) { "备份凭据无效" }
            row.remove("password"); row.put("secret", sourceSecrets.encrypt(password))
        }
        restored.getValue("media_sources_v1")["connections"] = rows.toString()
        val plex = restored.getValue("plex_connection_v1")
        plex.remove("client")
        (plex["server"] as? String)?.let { raw ->
            val server = JSONObject(raw); server.getString("id"); server.getString("machine"); server.getString("name")
            WebDavHttp.validateBase(server.getString("address"))
        }
        (plex["token"] as? String)?.let { token ->
            require(token.isNotBlank() && token.length <= 16_384) { "Plex凭据无效" }
            plex["token"] = DeviceSecrets("MegatronPlayer.PlexSecrets.v1").encrypt(token)
        }
        val directory = payload.getJSONObject("backupTarget")
        val target = BackupTarget(directory.getString("source"), WebDavHttp.cleanRelativePath(directory.getString("path")))
        require(target.sourceId in webDavIds) { "备份目录的WebDAV连接已缺失" }
        (restored.getValue("music_queue_v1")["items"] as? String)?.let {
            restored.getValue("music_queue_v1")["items"] = portableQueue(it)
        }
        val supplemental = restored.getValue("music_supplements_v1")
        com.megatron.player.music.metadata.MusicSupplementStore.validateBackup(supplemental)
        supplemental.keys.toList().forEach { key -> supplemental[key] = JSONObject(supplemental[key] as String).apply { remove("artPath") }.toString() }
        // A restored queue must stay silent until the user explicitly starts it.
        restored.getValue("music_ui_v1")["dismissed"] = true
        return PreparedBackup(restored, target)
    }
    fun apply(prepared: PreparedBackup, finalize: () -> Unit = {}) {
        val previous = (names + "nas_backup_v1" + "plex_pending_progress_v1").associateWith { prefs(it).all.toMap() }
        try {
            prepared.preferences.forEach { (name, values) -> write(prefs(name), values) }
            finalize()
            // Device identity stays new; old queued network writes must not be replayed after import.
            check(context.getSharedPreferences("plex_pending_progress_v1", 0).edit().clear().commit())
        } catch (error: Exception) {
            previous.forEach { (name, values) -> runCatching { write(prefs(name), values) } }
            throw error
        }
    }
    private fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val type = when (value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> error("不支持的配置类型") }
            put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
        }
    }
    private fun decode(data: JSONObject): Map<String, Any> {
        require(data.length() <= 10_000) { "备份配置过多" }
        return data.keys().asSequence().associateWith { key ->
            require(key.length <= 1024) { "备份配置键无效" }
            val row = data.getJSONObject(key)
            when (row.getString("type")) {
                "string" -> row.getString("value")
                "boolean" -> row.getBoolean("value")
                "int" -> row.getInt("value")
                "long" -> row.getLong("value")
                "float" -> row.getDouble("value").toFloat().also { require(it.isFinite()) }
                "set" -> row.getJSONArray("value").let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }
                else -> error("不支持的配置类型")
            }
        }
    }
    private fun write(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            else -> error("不支持的配置类型")
        } }
        check(edit.commit()) { "无法保存恢复的配置" }
    }
}
internal data class PreparedBackup(val preferences: Map<String, Map<String, Any>>, val target: BackupTarget, val passwordProtected: Boolean = true)
object BackupRestoreGuard { @Volatile var restoring: Boolean = false }
