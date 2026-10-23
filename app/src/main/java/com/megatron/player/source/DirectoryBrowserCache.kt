package com.megatron.player.source

import kotlinx.coroutines.*
import java.security.MessageDigest

/** Browse-only cache. Scanning and playback file validation deliberately keep their own freshness rules. */
object DirectoryBrowserCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = CoalescingCache<String, List<BrowserEntry>>(5 * 60_000, 48, 50_000, { it.size })
    private fun key(credentials: SourceCredentials, path: String): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(credentials.config.id, credentials.config.address, credentials.config.username, credentials.password,
            WebDavHttp.cleanRelativePath(path)).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun list(credentials: SourceCredentials, path: String, force: Boolean = false): List<BrowserEntry> =
        cache.get(key(credentials, path), scope, force) { WebDavClient().list(credentials, path) }
    fun invalidate(credentials: SourceCredentials, path: String) { cache.invalidate(key(credentials, path)) }
}
