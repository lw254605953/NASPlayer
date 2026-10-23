package com.megatron.player.plex

import android.content.Context
import com.megatron.player.source.DeviceSecrets
import org.json.JSONObject
import java.util.UUID

class PlexStore(context: Context) {
    internal val progressQueue = PlexProgressQueue(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("plex_connection_v1", Context.MODE_PRIVATE)
    private val secrets = DeviceSecrets("MegatronPlayer.PlexSecrets.v1")
    val clientIdentifier: String
        get() = synchronized(PlexStore::class.java) {
            prefs.getString("client", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client", it).apply() }
        }

    fun config(): PlexServerConfig? = prefs.getString("server", null)?.let { raw ->
        val item = JSONObject(raw)
        PlexServerConfig(item.getString("id"), item.getString("machine"), item.getString("name"), item.getString("address"))
    }

    fun credentials(id: String? = null): PlexCredentials? {
        val config = config() ?: return null
        if (id != null && id != config.id) return null
        val encrypted = prefs.getString("token", null) ?: return null
        return PlexCredentials(config, secrets.decrypt(encrypted))
    }

    fun save(credentials: PlexCredentials) {
        val config = credentials.config
        val item = JSONObject().put("id", config.id).put("machine", config.machineIdentifier)
            .put("name", config.name).put("address", config.address)
        val encrypted = secrets.encrypt(credentials.token)
        prefs.edit().putString("server", item.toString()).putString("token", encrypted).apply()
    }

    /** Updates only the local display name; no token decryption or server request is needed. */
    fun rename(name: String): PlexServerConfig? {
        val current = config() ?: return null
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "显示名称不能为空" }
        val item = JSONObject().put("id", current.id).put("machine", current.machineIdentifier)
            .put("name", trimmed).put("address", current.address)
        prefs.edit().putString("server", item.toString()).apply()
        return current.copy(name = trimmed)
    }

    /** Pending rows are visible/replayed only under the exact current server and token identity. */
    fun progressSyncSnapshot(): List<PlexPendingProgressInfo> {
        val identity = runCatching { credentials()?.let(::plexProgressIdentity) }.getOrNull() ?: return emptyList()
        return progressQueue.list(identity).sortedByDescending { it.updatedAtMs }.map {
            PlexPendingProgressInfo(it.ratingKey, it.title.ifBlank { "影片 ${it.ratingKey}" }, it.positionMs, it.durationMs, it.updatedAtMs, it.conflicted, it.message)
        }
    }

    /** Use local resume only when the supplied fresh metadata still matches its remote checkpoint. */
    fun preferredProgress(movie: PlexMovie): Long? {
        val identity = runCatching { credentials()?.let(::plexProgressIdentity) }.getOrNull() ?: return null
        val item = progressQueue.get(identity, movie.ratingKey) ?: return null
        val remote = PlexProgressCheckpoint(movie.viewOffsetMs, movie.watched, movie.lastViewedAt)
        return item.positionMs.takeIf { !item.conflicted && PlexProgressRules.mayReplay(item.baseline, remote, item.forceLocal) }
    }

    fun discardPendingProgress(ratingKey: String): Boolean {
        val identity = runCatching { credentials()?.let(::plexProgressIdentity) }.getOrNull() ?: return false
        return progressQueue.discard(identity, ratingKey)
    }

    fun resolveProgress(ratingKey: String, useLocal: Boolean): Boolean {
        val identity = runCatching { credentials()?.let(::plexProgressIdentity) }.getOrNull() ?: return false
        return progressQueue.resolve(identity, ratingKey, useLocal)
    }

    suspend fun retryPendingProgress() { PlexProgressSync.flush(this) }

    fun disconnect() { prefs.edit().remove("server").remove("token").apply() }
}
