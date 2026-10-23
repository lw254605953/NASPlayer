package com.megatron.player.plex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object PlexProgressSync {
    private val mutex = Mutex()
    private data class SessionKey(val identity: String, val session: String, val key: String)
    private val activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val acknowledged = java.util.concurrent.ConcurrentHashMap<SessionKey, PlexProgressCheckpoint>()
    fun rememberedCheckpoint(identity: String, session: String, key: String): PlexProgressCheckpoint? = acknowledged[SessionKey(identity, session, key)]
    fun endSession(session: String) {
        initializingSessions.remove(session)
        activeSessions.remove(session)
        acknowledged.keys.removeAll { it.session == session }
    }
    private val initializingSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun beginSession(session: String) { activeSessions.add(session); initializingSessions.add(session) }
    fun finishInitialization(session: String) { initializingSessions.remove(session) }
    internal data class Outcome(val checkpoint: PlexProgressCheckpoint? = null, val message: String, val retryRequired: Boolean = false)

    suspend fun flush(store: PlexStore) = withContext(Dispatchers.IO) {
        val credentials = runCatching { store.credentials() }.getOrNull() ?: return@withContext
        val identity = plexProgressIdentity(credentials)
        for (item in store.progressQueue.list(identity).filterNot { it.conflicted || it.session in initializingSessions }) {
            send(store, identity, item.ratingKey, PlexClient(store.clientIdentifier), null)
        }
    }

    suspend fun send(store: PlexStore, identity: String, key: String, client: PlexClient, trustedLiveSession: String?): Outcome = mutex.withLock {
        var entry = store.progressQueue.get(identity, key) ?: return@withLock Outcome(message = "已同步")
        if (entry.conflicted) return@withLock Outcome(message = "待处理：其他设备的进度或已看状态已变化", retryRequired = true)
        try {
            val credentials = store.credentials() ?: return@withLock Outcome(message = "连接已移除，进度已保存在本机")
            if (plexProgressIdentity(credentials) != identity) return@withLock Outcome(message = "账号或服务器已变化，进度留在原账号")
            val remote = client.detail(credentials, key)
            val checkpoint = remote.progressCheckpoint()
            if (entry.baseline == null && entry.session == trustedLiveSession) {
                entry = store.progressQueue.initializeBaseline(entry, checkpoint, remote.title) ?: return@withLock Outcome(message = "本机进度已变化，稍后同步")
            }
            val uninterruptedLiveReport = entry.session == trustedLiveSession && entry.baseline == checkpoint
            if (!uninterruptedLiveReport && !PlexProgressRules.mayReplay(entry.baseline, checkpoint, entry.forceLocal)) {
                store.progressQueue.replace(entry, entry.copy(title = entry.title.ifBlank { remote.title }, conflicted = true,
                    message = if (entry.baseline == null) "离线开始播放，无法确认服务器是否有更新，请选择使用哪份进度" else if (entry.baseline?.lastViewedAt == 0L || checkpoint.lastViewedAt == 0L) "服务器未提供可靠观看时间，请选择使用哪份进度" else "服务器进度或已看状态已变化，请选择使用哪份进度"))
                return@withLock Outcome(message = "待处理：请在设置中选择本机或服务器进度", retryRequired = true)
            }
            // Recheck identity after metadata I/O. Old queued credentials are never replayed into a new account.
            if (store.credentials()?.let(::plexProgressIdentity) != identity) return@withLock Outcome(message = "账号已变化，进度留在原账号")
            if (store.progressQueue.get(identity, key)?.revision != entry.revision) return@withLock Outcome(message = "本机进度已更新，等待同步")
            client.reportTimeline(credentials, key, entry.state, entry.positionMs, entry.durationMs)
            if (entry.completed && !remote.watched) client.markWatched(credentials, key)
            // Capture the server's acknowledged state, rather than guessing how Plex normalizes completion/offset.
            val acknowledged = client.detail(credentials, key).progressCheckpoint()
            if (!store.progressQueue.acknowledge(entry, acknowledged)) return@withLock Outcome(message = "同步成功，本机待同步状态保存失败")
            if (entry.session in activeSessions) this.acknowledged[SessionKey(identity, entry.session, key)] = acknowledged
            Outcome(acknowledged, "已同步")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = "未同步，已保存在本机：${plexErrorMessage(error)}"
            store.progressQueue.replace(entry, entry.copy(message = message))
            Outcome(message = message, retryRequired = true)
        }
    }
}

private fun PlexMovie.progressCheckpoint() = PlexProgressCheckpoint(viewOffsetMs, watched, lastViewedAt)

/** App-scoped foreground/network retry; no background service or recurring worker. */
class PlexProgressSyncCoordinator(context: Context) {
    private val store = PlexStore(context.applicationContext)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registered = false
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onForeground() }
    }
    fun start() {
        if (started) return
        started = true
        registered = runCatching { connectivity?.registerDefaultNetworkCallback(callback); connectivity != null }.getOrDefault(false)
        onForeground()
    }
    fun onForeground() { scope.launch { runCatching { store.retryPendingProgress() } } }
    fun close() {
        if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
        scope.cancel()
    }
}
