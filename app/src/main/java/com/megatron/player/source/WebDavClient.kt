package com.megatron.player.source

import android.util.Xml
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser

class WebDavClient {
    fun validateAddress(address: String): String = WebDavHttp.validateBase(address).toString()

    fun list(credentials: SourceCredentials, path: String = "", onCall: (okhttp3.Call) -> Unit = {}): List<BrowserEntry> {
        val base = WebDavHttp.validateBase(credentials.config.address)
        val relative = WebDavHttp.cleanRelativePath(path)
        val target = WebDavHttp.urlForPath(base, relative, trailingSlash = true)
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><displayname/><getcontentlength/><getlastmodified/><getetag/><resourcetype/></prop></propfind>"
            .toRequestBody(XML_TYPE)
        WebDavHttp.execute(credentials, target, "PROPFIND", body, Headers.headersOf("Depth", "1", "Content-Type", "application/xml; charset=utf-8"), onCall).use { response ->
            if (!response.isSuccessful) throw WebDavHttp.httpFailure(response.code)
            val responseBody = response.body ?: throw IOException("WebDAV returned an empty response")
            return parse(responseBody, base, response.request.url,
                WebDavHttp.relativePath(base, response.request.url) ?: throw IOException("WebDAV 重定向超出目录"))
        }
    }

    private fun parse(body: ResponseBody, base: HttpUrl, requestUrl: HttpUrl, requestedPath: String): List<BrowserEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(body.charStream())
        val result = ArrayList<BrowserEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) throw IOException("WebDAV XML 文档声明不受支持")
            if (event == XmlPullParser.START_TAG && parser.name == "response" && isDav(parser)) {
                parseResponse(parser, base, requestUrl, requestedPath)?.let { result += it }
            }
            event = parser.next()
        }
        return result.distinctBy { it.path }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun parseResponse(parser: XmlPullParser, base: HttpUrl, requestUrl: HttpUrl, requestedPath: String): BrowserEntry? {
        var href: String? = null
        var successful = false
        var name = ""
        var size = 0L
        var modified = 0L
        var etag: String? = null
        var directory = false
        val responseDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == responseDepth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "href" -> if (isDav(parser)) {
                        href = parser.nextText()
                    } else skip(parser)
                    "propstat" -> if (isDav(parser)) {
                        val prop = parsePropstat(parser)
                        if (prop.success) {
                            successful = true
                            if (prop.displayName.isNotBlank()) name = prop.displayName
                            if (prop.size >= 0) size = prop.size
                            if (prop.modified > 0) modified = prop.modified
                            etag = prop.etag
                            directory = directory || prop.directory
                        }
                    } else skip(parser)
                    else -> skip(parser)
                }
            }
            event = parser.next()
        }
        if (!successful) return null
        val absolute = href?.let { WebDavHttp.resolveHref(requestUrl, it) } ?: return null
        val relative = WebDavHttp.relativePath(base, absolute) ?: return null
        if (relative.isEmpty() || relative == requestedPath) return null
        val requestedSegments = requestedPath.split('/').filter { it.isNotEmpty() }
        val entrySegments = relative.split('/').filter { it.isNotEmpty() }
        if (entrySegments.size != requestedSegments.size + 1 || entrySegments.take(requestedSegments.size) != requestedSegments) return null
        val finalName = name.ifBlank { relative.substringAfterLast('/') }
        return BrowserEntry(finalName, relative, directory, size, modified, etag = etag)
    }

    private data class Prop(val success: Boolean, val displayName: String, val size: Long, val modified: Long, val directory: Boolean, val etag: String?)

    private fun parsePropstat(parser: XmlPullParser): Prop {
        var success = false
        var displayName = ""
        var size = -1L
        var modified = 0L
        var etag: String? = null
        var directory = false
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "status" -> if (isDav(parser)) success = parser.nextText().contains(" 200 ") else skip(parser)
                    "prop" -> parseProperties(parser) { property, value ->
                        when (property) {
                            "displayname" -> displayName = value
                            "getcontentlength" -> size = value.toLongOrNull() ?: -1L
                            "getlastmodified" -> modified = parseDate(value)
                            "getetag" -> etag = value
                            "resourcetype" -> directory = value == "collection"
                        }
                    }
                    else -> skip(parser)
                }
            }
            event = parser.next()
        }
        return Prop(success, displayName, size, modified, directory, etag)
    }

    private fun parseProperties(parser: XmlPullParser, consume: (String, String) -> Unit) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                val property = parser.name
                if (!isDav(parser)) {
                    skip(parser)
                } else if (property == "resourcetype") {
                    val propertyDepth = parser.depth
                    var collection = false
                    var nested = parser.next()
                    while (!(nested == XmlPullParser.END_TAG && parser.depth == propertyDepth)) {
                        if (nested == XmlPullParser.START_TAG && parser.name == "collection" && isDav(parser)) collection = true
                        nested = parser.next()
                    }
                    consume(property, if (collection) "collection" else "")
                } else if (property in setOf("displayname", "getcontentlength", "getlastmodified", "getetag")) {
                    consume(property, parser.nextText())
                } else skip(parser)
            }
            event = parser.next()
        }
    }

    private fun isDav(parser: XmlPullParser): Boolean = parser.namespace == "DAV:"

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) when (parser.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
        }
    }

    private fun parseDate(value: String): Long = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    companion object { private val XML_TYPE = "application/xml; charset=utf-8".toMediaType() }
}

internal object WebDavHttp {
    private const val MAX_REDIRECTS = 5
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).build()
    }

    private val metadataClient by lazy { client.newBuilder().callTimeout(35, TimeUnit.SECONDS).build() }
    // A write must not depend on retrying a stale pooled browse connection.
    private val mutationClient by lazy { metadataClient.newBuilder().retryOnConnectionFailure(false)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS)).build() }

    fun validateBase(address: String): HttpUrl {
        val raw = address.trim()
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw IllegalArgumentException("WebDAV 地址无效")
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https") || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("WebDAV 地址必须是无账号信息、无查询参数的绝对 HTTP(S) 地址")
        }
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("WebDAV 地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) throw IllegalArgumentException("WebDAV 地址格式不受支持")
        return url.newBuilder().encodedPath(if (url.encodedPath.endsWith('/')) url.encodedPath else "${url.encodedPath}/").build()
    }

    fun cleanRelativePath(path: String): String {
        // SourceUris.parse has already decoded URI path segments. A literal "%20" or "%25"
        // in a remote filename must therefore remain literal and be encoded only by HttpUrl.
        val decoded = path.trim('/')
        if (decoded.split('/').any { it == ".." || it == "." || it.contains('\\') }) throw IllegalArgumentException("WebDAV 路径无效")
        return decoded
    }

    fun urlForPath(base: HttpUrl, path: String, trailingSlash: Boolean = false): HttpUrl {
        val builder = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        val built = builder.build()
        return if (trailingSlash && !built.encodedPath.endsWith('/')) built.newBuilder().encodedPath("${built.encodedPath}/").build() else built
    }

    fun resolveHref(base: HttpUrl, href: String): HttpUrl? {
        val uri = runCatching { URI(href.trim()) }.getOrNull() ?: return null
        if (uri.query != null || uri.fragment != null) return null
        val resolved = base.toUri().resolve(uri).toString().toHttpUrlOrNull() ?: return null
        return if (sameOrigin(base, resolved)) resolved else null
    }

    fun relativePath(base: HttpUrl, target: HttpUrl): String? {
        if (!sameOrigin(base, target)) return null
        val root = base.pathSegments.dropLast(1)
        val targetSegments = target.pathSegments
        if (targetSegments.size < root.size || targetSegments.subList(0, root.size) != root) return null
        val relative = targetSegments.drop(root.size)
        if (relative.any { it == ".." || it == "." }) return null
        return relative.joinToString("/").trimEnd('/')
    }

    fun execute(credentials: SourceCredentials, url: HttpUrl, method: String, body: okhttp3.RequestBody?, headers: Headers = Headers.headersOf(), onCall: (okhttp3.Call) -> Unit = {}): Response {
        val base = validateBase(credentials.config.address)
        var current = url
        repeat(MAX_REDIRECTS + 1) { attempt ->
            if (relativePath(base, current) == null && current.encodedPath != base.encodedPath) throw IOException("WebDAV 请求超出配置目录")
            val request = Request.Builder().url(current).method(method, body).headers(headers)
                .header("Authorization", Credentials.basic(credentials.config.username, credentials.password, StandardCharsets.UTF_8)).build()
            val mutating = method == "MOVE" || method == "DELETE"
            val call = (if (mutating) mutationClient else if (method == "GET") client else metadataClient).newCall(request)
            onCall(call)
            val response = call.execute()
            if (response.code !in 300..399) return response
            if (mutating) { response.close(); throw IOException("文件操作遇到重定向，请核实 WebDAV 地址并刷新目录") }
            val location = response.header("Location")
            response.close()
            val next = location?.let { resolveHref(current, it) } ?: throw IOException("WebDAV 重定向无效")
            if (attempt == MAX_REDIRECTS) throw IOException("WebDAV 重定向次数过多")
            current = next
        }
        error("unreachable")
    }

    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    fun httpFailure(code: Int): IOException = IOException("WebDAV 请求失败（HTTP $code）")
}
