package com.megatron.player.plex

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Persist before sending; a conflated wakeup drains the latest durable value. */
class PlexProgressReporter(
    private val store: PlexStore,
    private val ref: PlexPlaybackRef,
    private val sessionIdentifier: String = java.util.UUID.randomUUID().toString(),
    private val title: String = "视频",
) {
    private val queue = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = PlexClient(store.clientIdentifier, sessionIdentifier)
    private val identity = runCatching { store.credentials(ref.serverId)?.let(::plexProgressIdentity) }.getOrNull()
    @Volatile private var checkpoint: PlexProgressCheckpoint? = null
    @Volatile var status: String = "等待播放"
        private set

    init {
        PlexProgressSync.beginSession(sessionIdentifier)
        scope.launch {
            var uninterrupted = true
            try {
                for (ignored in queue) {
                    val owner = identity ?: continue
                    val result = PlexProgressSync.send(store, owner, ref.ratingKey, client, sessionIdentifier.takeIf { uninterrupted })
                    if (result.retryRequired) uninterrupted = false
                    PlexProgressSync.finishInitialization(sessionIdentifier)
                    result.checkpoint?.let { checkpoint = it }
                    status = result.message
                }
            } finally { PlexProgressSync.endSession(sessionIdentifier); scope.cancel() }
        }
    }

    fun offer(state: String, position: Long, duration: Long, completed: Boolean = false) {
        if (duration <= 0 || position < 0 || state !in setOf("playing", "paused", "stopped", "buffering")) return
        val owner = identity ?: run { status = "Plex连接已移除"; return }
        if (!store.progressQueue.put(owner, ref.ratingKey, sessionIdentifier, state, position.coerceAtMost(duration), duration, completed, PlexProgressSync.rememberedCheckpoint(owner, sessionIdentifier, ref.ratingKey) ?: checkpoint, title)) {
            status = "本机进度保存失败"
            return
        }
        queue.trySend(Unit)
    }

    fun finish(position: Long, duration: Long, completed: Boolean) {
        offer("stopped", position, duration, completed)
        queue.close()
    }
}
