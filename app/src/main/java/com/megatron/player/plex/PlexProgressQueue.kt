package com.megatron.player.plex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Only a one-way identity fingerprint is persisted; server addresses and credentials are absent. */
internal fun plexProgressIdentity(credentials: PlexCredentials): String {
    val machine = credentials.config.machineIdentifier.takeIf { it.isNotBlank() } ?: credentials.config.id
    val digest = MessageDigest.getInstance("SHA-256").digest(credentials.token.toByteArray(Charsets.UTF_8))
    return "$machine:${digest.joinToString("") { "%02x".format(it) }}"
}

data class PlexPendingProgressInfo(
    val ratingKey: String, val title: String, val positionMs: Long, val durationMs: Long,
    val updatedAtMs: Long, val conflicted: Boolean, val message: String,
)

internal data class PlexPendingProgress(
    val identity: String, val ratingKey: String, val session: String, val revision: String,
    val state: String, val positionMs: Long, val durationMs: Long, val completed: Boolean,
    val updatedAtMs: Long, val title: String = "", val baseline: PlexProgressCheckpoint? = null,
    val conflicted: Boolean = false, val message: String = "等待同步", val forceLocal: Boolean = false,
)

/** At most one latest value per account/server/title and 200 titles overall. */
internal class PlexProgressQueue(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("plex_pending_progress_v1", Context.MODE_PRIVATE)
    private fun read(): MutableList<PlexPendingProgress> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index -> runCatching {
            val j = array.getJSONObject(index)
            PlexPendingProgress(j.getString("identity"), j.getString("key"), j.getString("session"), j.getString("revision"),
                j.getString("state"), j.getLong("position"), j.getLong("duration"), j.optBoolean("completed"), j.getLong("updated"),
                j.optString("title"), j.optJSONObject("baseline")?.let { PlexProgressCheckpoint(it.getLong("position"), it.getBoolean("watched"), it.getLong("viewed")) },
                j.optBoolean("conflict"), j.optString("message", "等待同步"), j.optBoolean("force"))
        }.getOrNull() }.toMutableList()
    }
    private fun write(items: List<PlexPendingProgress>): Boolean {
        val array = JSONArray()
        items.sortedByDescending { it.updatedAtMs }.take(200).forEach { p ->
            array.put(JSONObject().put("identity", p.identity).put("key", p.ratingKey).put("session", p.session).put("revision", p.revision)
                .put("state", p.state).put("position", p.positionMs).put("duration", p.durationMs).put("completed", p.completed)
                .put("updated", p.updatedAtMs).put("title", p.title).put("conflict", p.conflicted).put("message", p.message).put("force", p.forceLocal)
                .apply { p.baseline?.let { put("baseline", JSONObject().put("position", it.positionMs).put("watched", it.watched).put("viewed", it.lastViewedAt)) } })
        }
        return prefs.edit().putString("items", array.toString()).commit()
    }
    fun list(identity: String): List<PlexPendingProgress> = synchronized(lock) { read().filter { it.identity == identity } }
    fun get(identity: String, key: String): PlexPendingProgress? = list(identity).firstOrNull { it.ratingKey == key }
    fun put(identity: String, key: String, session: String, state: String, position: Long, duration: Long, completed: Boolean, checkpoint: PlexProgressCheckpoint? = null, title: String = ""): Boolean = synchronized(lock) {
        val items = read()
        val old = items.firstOrNull { it.identity == identity && it.ratingKey == key }
        val sameSession = old?.session == session
        items.removeAll { it.identity == identity && it.ratingKey == key }
        items.add(PlexPendingProgress(identity, key, session, UUID.randomUUID().toString(), state, position, duration, completed, System.currentTimeMillis(),
            title = title.ifBlank { old?.title.orEmpty() }, baseline = if (sameSession) old?.baseline ?: checkpoint else checkpoint,
            conflicted = old?.conflicted == true && sameSession, message = if (sameSession) old?.message ?: "等待同步" else "等待同步"))
        write(items)
    }
    fun initializeBaseline(original: PlexPendingProgress, checkpoint: PlexProgressCheckpoint, title: String): PlexPendingProgress? = synchronized(lock) {
        val items = read(); val index = items.indexOfFirst { it.identity == original.identity && it.ratingKey == original.ratingKey && it.session == original.session }
        if (index < 0) null else {
            val current = items[index]
            val next = current.copy(baseline = current.baseline ?: checkpoint, title = current.title.ifBlank { title })
            items[index] = next
            if (write(items)) next else null
        }
    }
    fun replace(original: PlexPendingProgress, replacement: PlexPendingProgress): Boolean = synchronized(lock) {
        val items = read(); val index = items.indexOfFirst { it.identity == original.identity && it.ratingKey == original.ratingKey && it.revision == original.revision }
        if (index < 0) false else { items[index] = replacement; write(items) }
    }
    /** A delayed acknowledgement may update the next checkpoint, but cannot delete a newer update. */
    fun acknowledge(original: PlexPendingProgress, checkpoint: PlexProgressCheckpoint): Boolean = synchronized(lock) {
        val items = read(); val index = items.indexOfFirst { it.identity == original.identity && it.ratingKey == original.ratingKey }
        if (index < 0) true else {
            val next = items[index]
            if (next.revision == original.revision) items.removeAt(index)
            else if (next.session == original.session) items[index] = next.copy(baseline = checkpoint, conflicted = false, message = "等待同步")
            write(items)
        }
    }
    fun discard(identity: String, key: String): Boolean = synchronized(lock) { val items = read(); items.removeAll { it.identity == identity && it.ratingKey == key }; write(items) }
    fun resolve(identity: String, key: String, useLocal: Boolean): Boolean = synchronized(lock) {
        if (!useLocal) return@synchronized discard(identity, key)
        val item = get(identity, key) ?: return@synchronized false
        replace(item, item.copy(forceLocal = true, conflicted = false, message = "等待使用本机进度同步"))
    }
    companion object { private val lock = Any() }
}
