package com.megatron.player.source

import android.net.Uri

enum class SourceKind { WEBDAV, LOCAL }

data class MediaSourceConfig(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val address: String,
    val username: String = "",
)

data class SourceCredentials(val config: MediaSourceConfig, val password: String)

/** WebDAV paths are relative to the configured root; local paths are document IDs. */
data class BrowserEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val documentUri: String? = null,
    val etag: String? = null,
)

object SourceUris {
    const val SCHEME = "mpdav"
    fun build(sourceId: String, path: String): Uri = Uri.Builder().scheme(SCHEME).authority(sourceId)
        .apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()
    fun parse(uri: Uri): Pair<String, String>? {
        if (uri.scheme != SCHEME) return null
        val sourceId = uri.host?.takeIf { it.isNotBlank() } ?: return null
        return sourceId to uri.pathSegments.joinToString("/")
    }
}
