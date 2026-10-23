package com.megatron.player.plex

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.megatron.player.source.SourceCredentials
import com.megatron.player.source.SourceUris
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class PlexDataSourceFactory(
    private val fallback: DataSource.Factory,
    private val credentials: (String) -> PlexCredentials?,
    private val clientIdentifier: String,
    private val onReadStage: (String) -> Unit = {},
    private val playbackSession: PlexPlaybackSession = PlexPlaybackSession(clientIdentifier),
    private val webDavBinding: (String) -> PlexWebDavBinding? = { null },
    private val sourceCredentials: (String) -> SourceCredentials? = { null },
) : DataSource.Factory {
    override fun createDataSource(): DataSource = PlexRoutingDataSource(fallback, credentials, clientIdentifier, onReadStage, playbackSession, webDavBinding, sourceCredentials)
}

private class PlexRoutingDataSource(
    private val fallback: DataSource.Factory,
    private val credentials: (String) -> PlexCredentials?,
    private val clientIdentifier: String,
    private val onReadStage: (String) -> Unit,
    private val playbackSession: PlexPlaybackSession,
    private val webDavBinding: (String) -> PlexWebDavBinding?,
    private val sourceCredentials: (String) -> SourceCredentials?,
) : BaseDataSource(/* isNetwork = */ true) {
    private var delegate: DataSource? = null
    private var remote: RemoteStream? = null
    private var currentSpec: DataSpec? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    @Volatile private var activeCall: Call? = null

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        val ref = PlexUris.parse(dataSpec.uri)
        if (ref == null) return openFallback(dataSpec)

        onReadStage("读取 Plex 授权配置")
        val supplied = credentials(ref.serverId) ?: throw IOException("找不到 Plex 服务器配置")
        if (supplied.config.id != ref.serverId) throw IOException("Plex 服务器配置无效")
        val base = supplied.config.address.toHttpUrlOrNull() ?: throw IOException("Plex 服务器地址无效")
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) throw IOException("Plex 服务器地址格式不受支持")
        val binding = webDavBinding(ref.serverId)
        if (binding != null) {
            onReadStage("读取 WebDAV 真实目录并定位影片文件")
            val path = playbackSession.prepareWebDav(supplied, ref, binding, sourceCredentials(binding.sourceId)) { call ->
                activeCall = call; playbackSession.trackCall(call)
            }
            activeCall = null
            onReadStage("连接 WebDAV · 起始字节 ${dataSpec.position}")
            return try {
                val length = openFallback(dataSpec.withUri(SourceUris.build(binding.sourceId, path)))
                onReadStage("读取 WebDAV 视频 · 起始字节 ${dataSpec.position} · 长度 $length")
                length
            } catch (error: IOException) {
                val status = Regex("WebDAV 请求失败（HTTP ([0-9]{3})）").matchEntire(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                if (status !in setOf(401, 403, 404)) throw error
                val message = when (status) {
                    401, 403 -> "WebDAV 无权读取视频，请检查账号、密码和目录权限"
                    404 -> "WebDAV 找不到视频文件，请检查目录对应关系"
                    else -> "WebDAV 视频读取失败，请检查绑定连接、目录和网络"
                }
                throw PlexPlaybackFailure(message, httpStatus = status, cause = error)
            }
        }
        onReadStage("校验 Plex 媒体路径")
        val target = partUrl(base, ref.partKey)
        onReadStage("正在确认 Plex 播放许可")
        val decision = playbackSession.prepare(supplied, ref) { call -> activeCall = call; playbackSession.trackCall(call) }
        onReadStage("Plex 允许直放 · general=${decision.generalCode} · directPlay=${decision.directPlayCode}")
        transferInitializing(dataSpec)
        val position = dataSpec.position
        if (position < 0L) throw IOException("Plex 读取位置无效")
        if (dataSpec.length == 0L) {
            remaining = 0L
            transferStarted(dataSpec)
            opened = true
            return 0L
        }
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else {
            if (dataSpec.length < 0L || dataSpec.length > Long.MAX_VALUE - position) throw IOException("Plex 读取范围过大")
            position + dataSpec.length - 1
        }
        val range = if (position > 0L || end != null) "bytes=$position-${end ?: ""}" else null
        val requestHeaders = if (range == null) Headers.headersOf() else Headers.headersOf("Range", range)
        onReadStage("连接 Plex · 起始字节 $position")
        val response = PlexHttp.execute(supplied, target, requestHeaders, clientIdentifier, playbackSession.sessionIdentifier) {
            activeCall = it; playbackSession.trackCall(it)
        }
        onReadStage("Plex 返回 HTTP ${response.code} · 起始字节 $position")
        activeCall = null
        try {
            if (response.code in setOf(401, 403, 404)) throw PlexPlaybackFailure(
                if (response.code == 403) "Plex 已允许直放，但仍拒绝读取视频文件" else "Plex 无法读取所选视频文件",
                httpStatus = response.code, decision = decision,
            )
            if (!response.isSuccessful) throw PlexHttp.httpFailure(response.code)
            if (position > 0L && response.code != 206) throw IOException("Plex 服务不支持从指定位置读取")
            val body = response.body ?: throw IOException("Plex 返回空内容")
            if (response.code == 206) validateContentRange(response.header("Content-Range"), position)
            val bodyLength = body.contentLength()
            val logicalLength = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                bodyLength >= 0L -> bodyLength
                else -> C.LENGTH_UNSET.toLong()
            }
            remote = RemoteStream(response, body.byteStream())
            onReadStage("读取 Plex 内容 · HTTP ${response.code} · 起始字节 $position · 长度 $logicalLength")
            remaining = logicalLength
            transferStarted(dataSpec)
            opened = true
            return logicalLength
        } catch (error: IOException) {
            activeCall = null
            response.close()
            throw error
        }
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        val source = fallback.createDataSource()
        delegate = source
        transferInitializing(dataSpec)
        return try {
            val length = source.open(dataSpec)
            transferStarted(dataSpec)
            remaining = length
            opened = true
            length
        } catch (error: IOException) {
            delegate = null
            runCatching { source.close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val active = remote
        if (active != null) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
            val count = active.stream.read(buffer, offset, requested)
            if (count == -1) {
                if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("Plex 内容提前结束")
                remaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
            bytesTransferred(count)
            return count
        }
        val source = delegate ?: throw IOException("数据源未打开")
        val count = source.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        remote?.responseHeaders ?: delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        var failure: IOException? = null
        activeCall?.cancel()
        activeCall = null
        try { remote?.close() } catch (error: IOException) { failure = error }
        remote = null
        try { delegate?.close() } catch (error: IOException) { if (failure == null) failure = error }
        delegate = null
        if (opened) transferEnded()
        opened = false
        remaining = C.LENGTH_UNSET.toLong()
        currentSpec = null
        failure?.let { throw it }
    }

    private fun partUrl(base: HttpUrl, partKey: String): HttpUrl {
        val uri = runCatching { URI(partKey) }.getOrNull() ?: throw IOException("Plex 媒体路径无效")
        if (uri.isAbsolute || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.rawPath.isNullOrBlank() || !uri.rawPath.startsWith("/library/parts/")) {
            throw IOException("Plex 媒体路径无效")
        }
        if (uri.path.orEmpty().split('/').any { it == "." || it == ".." }) throw IOException("Plex 媒体路径无效")
        val result = base.newBuilder().encodedPath(withBasePath(base, uri.rawPath)).addQueryParameter("download", "0").build()
        if (result.pathSegments.any { it == "." || it == ".." }) throw IOException("Plex 媒体路径无效")
        return result
    }

    private fun withBasePath(base: HttpUrl, path: String): String =
        base.encodedPath.trimEnd('/').let { prefix -> if (prefix.isEmpty()) path else prefix + path }

    private fun validateContentRange(value: String?, expectedStart: Long) {
        val matcher = value?.let { CONTENT_RANGE.matcher(it) }
        if (matcher == null || !matcher.matches() || matcher.group(1).toLongOrNull() != expectedStart) throw IOException("Plex 返回的范围无效")
    }

    private data class RemoteStream(val response: Response, val stream: InputStream) {
        val responseHeaders: Map<String, List<String>> get() = response.headers.toMultimap()
        fun close() { response.close() }
    }

    companion object {
        private val CONTENT_RANGE = Pattern.compile("bytes (\\d+)-\\d+/(?:\\d+|\\*)", Pattern.CASE_INSENSITIVE)
    }
}

private object PlexHttp {
    private const val MAX_REDIRECTS = 5
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).build()
    }

    fun execute(credentials: PlexCredentials, url: HttpUrl, extraHeaders: Headers, clientIdentifier: String, sessionIdentifier: String, onCall: (Call) -> Unit): Response {
        val base = credentials.config.address.toHttpUrlOrNull() ?: throw IOException("Plex 服务器地址无效")
        var current = url
        repeat(MAX_REDIRECTS + 1) { attempt ->
            if (!sameOrigin(base, current)) throw IOException("Plex 重定向超出服务器范围")
            val request = Request.Builder().url(current).headers(extraHeaders)
                .header("X-Plex-Token", credentials.token)
                .header("X-Plex-Client-Identifier", clientIdentifier)
                .header("X-Plex-Product", "NASPlayer")
                .header("X-Plex-Platform", "Android")
                .header("X-Plex-Pms-Api-Version", "1.0")
                .header("X-Plex-Session-Identifier", sessionIdentifier)
                .header("Accept-Encoding", "identity")
                .header("Accept", "*/*")
                .get().build()
            val call = client.newCall(request)
            onCall(call)
            val response = call.execute()
            if (response.code !in 300..399) return response
            val location = response.header("Location")
            response.close()
            val next = location?.let { resolve(current, it) } ?: throw IOException("Plex 重定向无效")
            if (attempt == MAX_REDIRECTS) throw IOException("Plex 重定向次数过多")
            current = next
        }
        error("unreachable")
    }

    private fun resolve(base: HttpUrl, location: String): HttpUrl? {
        val uri = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.fragment != null) return null
        return base.toUri().resolve(uri).toString().toHttpUrlOrNull()?.takeIf { sameOrigin(base, it) }
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    fun httpFailure(code: Int): IOException = IOException("Plex 请求失败（HTTP $code）")
}
