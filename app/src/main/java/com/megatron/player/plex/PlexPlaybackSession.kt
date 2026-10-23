package com.megatron.player.plex

import okhttp3.Call
import com.megatron.player.source.MediaSourceConfig
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceCredentials
import com.megatron.player.source.WebDavClient
import com.megatron.player.source.BrowserEntry
import java.util.UUID

/** One negotiated decision per player. Seeks reuse it; an explicit retry starts a new decision. */
class PlexPlaybackSession(private val clientIdentifier: String,
    private val learnedPaths: (String, MediaSourceConfig) -> List<PlexPathMapping> = { _, _ -> emptyList() },
    private val rememberPath: (String, MediaSourceConfig, PlexWebDavBinding, PlexPathMapping) -> Unit = { _, _, _, _ -> },
    private val directoryReader: (SourceCredentials, String, (Call) -> Unit) -> List<BrowserEntry> = { source, path, onCall -> WebDavClient().list(source, path, onCall) }) {
    val sessionIdentifier: String = UUID.randomUUID().toString()
    private val client = PlexClient(clientIdentifier, sessionIdentifier)
    private data class CacheKey(val ref: PlexPlaybackRef, val address: String, val token: String)
    private var cachedKey: CacheKey? = null
    private data class WebDavKey(val ref: PlexPlaybackRef, val address: String, val token: String, val binding: PlexWebDavBinding, val source: SourceCredentials)
    private var webDavKey: WebDavKey? = null
    private var webDavPath: String? = null
    private var generation = 0L
    @Volatile private var activeCall: Call? = null
    @Volatile private var released = false
    @Volatile var decision: PlexPlaybackDecision? = null
        private set
    @Volatile var videoSource: String = "Plex"
        private set

    fun prepare(credentials: PlexCredentials, ref: PlexPlaybackRef, onCall: (Call) -> Unit): PlexPlaybackDecision {
        videoSource = "Plex"
        val key = CacheKey(ref, credentials.config.address, credentials.token)
        val snapshot = synchronized(this) {
            if (cachedKey == key) decision?.let { it.requireDirectPlay(); return it }
            generation
        }
        val loaded = client.directPlayDecision(credentials, ref, onCall)
        synchronized(this) {
            if (snapshot != generation || released) throw java.io.InterruptedIOException("Plex playback session ended")
            cachedKey = key; decision = loaded
        }
        loaded.requireDirectPlay()
        return loaded
    }

    fun prepareWebDav(credentials: PlexCredentials, ref: PlexPlaybackRef, binding: PlexWebDavBinding,
        sourceCredentials: SourceCredentials?, onCall: (Call) -> Unit): String {
        videoSource = "WebDAV（Plex资料）"
        decision = null
        val source = sourceCredentials?.config
        if (sourceCredentials == null || source == null || source.id != binding.sourceId || source.kind != SourceKind.WEBDAV)
            throw PlexPlaybackFailure("绑定的 WebDAV 连接已删除，请在设置中重新选择")
        val key = WebDavKey(ref, credentials.config.address, credentials.token, binding, sourceCredentials)
        val snapshot = synchronized(this) {
            if (webDavKey == key) webDavPath?.let { return it }
            generation
        }
        val loaded = try {
            val file = client.fileLocation(credentials, ref, onCall)
            val matched = PlexWebDavAutoResolver.resolve(file, binding, learnedPaths(ref.serverId, source)) { path ->
                if (released || snapshot != synchronized(this) { generation }) throw java.io.InterruptedIOException("Plex playback session ended")
                directoryReader(sourceCredentials, path, onCall)
            }
            if (!released && snapshot == synchronized(this) { generation }) rememberPath(ref.serverId, source, binding, matched.mapping)
            matched.path
        } catch (error: IllegalArgumentException) {
            throw PlexPlaybackFailure("Plex 或 WebDAV 返回的文件位置无效", cause = error)
        } catch (error: java.io.IOException) {
            val status = Regex("WebDAV 请求失败（HTTP ([0-9]{3})）").matchEntire(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (status in setOf(401, 403)) throw PlexPlaybackFailure("WebDAV 无权读取影片目录，请检查该连接的账号及共享权限", httpStatus = status, cause = error)
            throw error
        }
        synchronized(this) {
            if (snapshot != generation || released) throw java.io.InterruptedIOException("Plex playback session ended")
            webDavKey = key; webDavPath = loaded
        }
        return loaded
    }

    fun trackCall(call: Call) { activeCall = call; if (released) call.cancel() }
    fun reset() = synchronized(this) {
        activeCall?.cancel(); activeCall = null
        generation++; cachedKey = null; decision = null; webDavKey = null; webDavPath = null
    }
    fun release() { released = true; reset() }
}
