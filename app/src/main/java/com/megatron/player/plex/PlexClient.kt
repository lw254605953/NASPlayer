package com.megatron.player.plex

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject

class PlexClient(private val clientIdentifier: String, private val sessionIdentifier: String = UUID.randomUUID().toString()) {
    private val http = PlexApiHttp(clientIdentifier)

    fun createPin(): PlexPin {
        val url = "https://plex.tv/api/v2/pins?strong=true".toHttpUrlOrNull() ?: error("Plex 授权地址无效")
        val json = http.request(url, "POST", null, authHeaders(true))
        val id = json.optInt("id", 0)
        val code = json.optString("code")
        if (id <= 0 || code.isBlank()) throw IOException("Plex 授权 PIN 响应无效")
        val authUrl = "https://app.plex.tv/auth#?clientID=${urlEncode(clientIdentifier)}&code=${urlEncode(code)}&context%5Bdevice%5D%5Bproduct%5D=NASPlayer"
        return PlexPin(id, code, authUrl, json.optInt("expiresIn", json.optInt("expiresInSeconds", 0)))
    }

    fun pollPin(pin: PlexPin): String? {
        val url = "https://plex.tv/api/v2/pins/${pin.id}".toHttpUrlOrNull() ?: throw IOException("Plex 授权地址无效")
        val json = http.request(url, "GET", null, authHeaders(true))
        return json.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
    }

    fun servers(accountToken: String): List<PlexServerOption> {
        requireToken(accountToken)
        val url = "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1".toHttpUrlOrNull()!!
        val array = http.requestArray(url, authHeaders(true, accountToken))
        return parseServerResources(array)
    }

    internal fun parseServerResources(array: JSONArray): List<PlexServerOption> {
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val provides = item.optString("provides")
            if (provides.isNotBlank() && provides.split(',').none { it.trim().equals("server", ignoreCase = true) }) return@mapNotNull null
            // Account resources identify servers by clientIdentifier; PMS /identity uses machineIdentifier.
            val machine = item.optString("clientIdentifier").ifBlank { item.optString("machineIdentifier") }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name", machine)
            val token = item.optString("accessToken").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val connections = item.optJSONArray("connections")?.let { parseConnections(it) } ?: emptyList()
            PlexServerOption(machine, name, token, connections)
        }
    }

    fun connect(address: String, token: String, id: String = UUID.randomUUID().toString(), name: String = ""): PlexCredentials {
        requireToken(token)
        val base = validateServerAddress(address)
        val identity = http.request(endpoint(base, listOf("identity")), "GET", null, authHeaders(true, token))
        val machine = identity.optString("machineIdentifier").takeIf { it.isNotBlank() }
            ?: identity.optJSONObject("MediaContainer")?.optString("machineIdentifier")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Plex 服务器身份响应无效")
        val serverName = name.ifBlank { identity.optString("name").takeIf { it.isNotBlank() } ?: "Plex服务器" }
        http.request(endpoint(base, listOf("library", "sections")), "GET", null, authHeaders(true, token))
        return PlexCredentials(PlexServerConfig(id, machine, serverName, base.toString()), token)
    }

    fun libraries(credentials: PlexCredentials): List<PlexLibrary> {
        val json = get(credentials, listOf("library", "sections"))
        val container = container(json)
        val dirs = container.optJSONArray("Directory") ?: JSONArray()
        return (0 until dirs.length()).mapNotNull { index ->
            val item = dirs.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val locations = item.optJSONArray("Location")
            PlexLibrary(key, item.optString("title", key), item.optString("type"),
                if (locations == null) emptyList() else (0 until locations.length()).mapNotNull { locations.optJSONObject(it)?.optString("path")?.takeIf(String::isNotBlank) })
        }
    }

    fun movies(credentials: PlexCredentials, libraryKey: String, query: String = "", offset: Int = 0, limit: Int = 60, sort: PlexSort = PlexSort.RELEASED, descending: Boolean = true, filters: PlexLibraryFilter = PlexLibraryFilter()): PlexMoviePage {
        if (offset < 0 || limit <= 0 || limit > 200) throw IllegalArgumentException("Plex 分页参数无效")
        val key = libraryKey.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: throw IllegalArgumentException("Plex 媒体库编号无效")
        val url = movieListUrl(credentials, key, query, sort, descending, filters)
        val json = http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true), mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to limit.toString()))
        val container = container(json)
        val metadata = container.optJSONArray("Metadata") ?: JSONArray()
        val movies = (0 until metadata.length()).mapNotNull { index -> PlexMetadataParser.parseMovie(metadata.optJSONObject(index)) }
        val inferredEnd = offset + movies.size + if (metadata.length() >= limit) 1 else 0
        val total = container.optInt("totalSize", container.optInt("total", inferredEnd))
        return PlexMoviePage(movies, total, container.optInt("offset", offset))
    }

    fun shows(credentials: PlexCredentials, libraryKey: String, query: String = "", offset: Int = 0, limit: Int = 60, sort: PlexSort = PlexSort.RELEASED, descending: Boolean = true, filters: PlexLibraryFilter = PlexLibraryFilter()): PlexMoviePage {
        return listSeries(credentials, libraryKey, query, offset, limit, sort, descending, filters)
    }

    fun children(credentials: PlexCredentials, ratingKey: String, offset: Int = 0, limit: Int = 100): PlexMoviePage {
        if (offset < 0 || limit <= 0 || limit > 200) throw IllegalArgumentException("Plex 分页参数无效")
        val key = numericKey(ratingKey)
        val url = endpoint(credentialsBase(credentials), listOf("library", "metadata", key, "children"))
        val json = http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true), mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to limit.toString()))
        return parseMoviePage(json, offset, limit)
    }

    fun allEpisodes(credentials: PlexCredentials, showKey: String): List<PlexMovie> {
        val key = numericKey(showKey)
        val found = LinkedHashMap<String, PlexMovie>()
        val seenRawKeys = HashSet<String>()
        var requestOffset = 0
        var previousNext = -1
        val limit = 100
        while (true) {
            val url = endpoint(credentialsBase(credentials), listOf("library", "metadata", key, "allLeaves"))
            val json = http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true), mapOf("X-Plex-Container-Start" to requestOffset.toString(), "X-Plex-Container-Size" to limit.toString()))
            val container = container(json)
            val metadata = container.optJSONArray("Metadata") ?: JSONArray()
            val total = container.optInt("totalSize", -1)
            if (metadata.length() == 0) {
                if (total >= 0 && requestOffset < total) throw IOException("Plex 剧集分页提前结束")
                break
            }
            val pageOffset = container.optInt("offset", requestOffset)
            if (pageOffset != requestOffset) throw IOException("Plex 剧集分页偏移异常")
            val rawKeys = (0 until metadata.length()).mapNotNull { metadata.optJSONObject(it)?.optString("ratingKey")?.takeIf { key -> key.isNotBlank() } }
            if (rawKeys.isEmpty()) throw IOException("Plex 剧集分页缺少标识")
            val newRawKeys = rawKeys.filter { seenRawKeys.add(it) }
            if (newRawKeys.isEmpty()) throw IOException("Plex 剧集分页重复")
            val next = requestOffset + metadata.length()
            if (next <= requestOffset || next <= previousNext) throw IOException("Plex 剧集分页未前进")
            previousNext = next
            for (index in 0 until metadata.length()) {
                PlexMetadataParser.parseMovie(metadata.optJSONObject(index))?.takeIf { it.type == "episode" && (it.grandparentRatingKey.isBlank() || it.grandparentRatingKey == key) }?.let { found.putIfAbsent(it.ratingKey, it) }
            }
            if ((total >= 0 && next >= total) || (total < 0 && metadata.length() < limit)) break
            requestOffset = next
        }
        return found.values.sortedWith(compareBy<PlexMovie> { if (it.parentIndex < 0) Int.MAX_VALUE else it.parentIndex }.thenBy { if (it.index < 0) Int.MAX_VALUE else it.index }.thenBy { it.ratingKey })
    }

    private fun listSeries(credentials: PlexCredentials, libraryKey: String, query: String, offset: Int, limit: Int, sort: PlexSort, descending: Boolean, filters: PlexLibraryFilter): PlexMoviePage {
        if (offset < 0 || limit <= 0 || limit > 200) throw IllegalArgumentException("Plex 分页参数无效")
        val key = libraryKey.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: throw IllegalArgumentException("Plex 媒体库编号无效")
        val url = endpoint(credentialsBase(credentials), listOf("library", "sections", key, "all")).newBuilder()
            .addQueryParameter("type", "2").addQueryParameter("sort", "${sort.field}:${if (descending) "desc" else "asc"}")
            .apply { if (query.isNotBlank()) addQueryParameter("title", query); applyLibraryFilters(this, filters, series = true) }.build()
        return parseMoviePage(http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true), mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to limit.toString())), offset, limit)
    }

    private fun parseMoviePage(json: JSONObject, offset: Int, limit: Int): PlexMoviePage {
        val container = container(json)
        val metadata = container.optJSONArray("Metadata") ?: JSONArray()
        val movies = (0 until metadata.length()).mapNotNull { PlexMetadataParser.parseMovie(metadata.optJSONObject(it)) }
        val inferred = offset + movies.size + if (metadata.length() >= limit) 1 else 0
        val total = container.optInt("totalSize", container.optInt("total", inferred))
        return PlexMoviePage(movies, total, container.optInt("offset", offset))
    }

    internal fun movieListUrl(credentials: PlexCredentials, libraryKey: String, query: String, sort: PlexSort, descending: Boolean, filters: PlexLibraryFilter = PlexLibraryFilter()): HttpUrl {
        val key = libraryKey.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: throw IllegalArgumentException("Plex 媒体库编号无效")
        return endpoint(credentialsBase(credentials), listOf("library", "sections", key, "all")).newBuilder()
            .addQueryParameter("type", "1").addQueryParameter("sort", "${sort.field}:${if (descending) "desc" else "asc"}")
            .apply { if (query.isNotBlank()) addQueryParameter("title", query); applyLibraryFilters(this, filters, series = false) }.build()
    }

    private fun applyLibraryFilters(builder: HttpUrl.Builder, filters: PlexLibraryFilter, series: Boolean) {
        require(filters.minimumRating in 0..10) { "评分筛选无效" }
        when (filters.watched) {
            PlexWatchFilter.ALL -> Unit
            PlexWatchFilter.UNWATCHED -> if (series) builder.addQueryParameter("show.unviewedLeafCount>", "1") else builder.addQueryParameter("unwatched", "1")
            PlexWatchFilter.WATCHED -> if (series) {
                builder.addQueryParameter("show.unviewedLeafCount", "0")
            } else builder.addQueryParameter("unwatched", "0")
        }
        if (filters.genre.isNotBlank()) builder.addQueryParameter("genre", filters.genre)
        if (filters.year.isNotBlank()) builder.addQueryParameter("year", filters.year)
        if (filters.minimumRating > 0) builder.addQueryParameter("audienceRating>", filters.minimumRating.toString())
    }

    /** Discover choices using server-provided keys, rather than inventing fixed facet paths. */
    fun libraryFacets(credentials: PlexCredentials, libraryKey: String, libraryType: String): PlexLibraryFacets {
        val key = numericKey(libraryKey)
        val base = credentialsBase(credentials)
        val url = endpoint(base, listOf("library", "sections", key, "all")).newBuilder()
            .addQueryParameter("includeMeta", "1").addQueryParameter("includeAdvanced", "1")
            .addQueryParameter("type", if (libraryType == "show") "2" else "1").build()
        val json = http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true),
            mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "0"))
        val c = container(json)
        val types = c.optJSONObject("Meta")?.optJSONArray("Type") ?: JSONArray()
        val type = (0 until types.length()).mapNotNull { types.optJSONObject(it) }.firstOrNull { it.optString("type") == libraryType }
        val filters = type?.optJSONArray("Filter") ?: c.optJSONArray("Directory") ?: JSONArray()
        fun choices(field: String): List<PlexFacet> {
            val filter = (0 until filters.length()).mapNotNull { filters.optJSONObject(it) }.firstOrNull {
                it.optString("filter") == field || it.optString("key").substringBefore('?').substringAfterLast('/') == field
            } ?: return emptyList()
            val raw = filter.optString("key")
            val uri = runCatching { URI(raw) }.getOrNull() ?: throw IOException("Plex 筛选路径无效")
            require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null && !hasCredentialQuery(uri.rawQuery))
            val path = if (uri.rawPath.startsWith('/')) uri.rawPath else "/library/sections/$key/${uri.rawPath}"
            require(path.startsWith("/library/sections/$key/") && uri.path.split('/').none { it == "." || it == ".." })
            val target = base.newBuilder().encodedPath(withBasePath(base, path)).encodedQuery(uri.rawQuery).build()
            val response = container(http.request(target, "GET", null, authHeaders(true, credentials.token, metadata = true)))
            val rows = response.optJSONArray("Directory") ?: JSONArray()
            return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let {
                val value = it.optString("key").takeIf { value -> value.isNotBlank() && value.length <= 200 } ?: return@let null
                PlexFacet(value, it.optString("title", value))
            } }.distinctBy { it.key }
        }
        return PlexLibraryFacets(choices("genre"), choices("year"))
    }

    fun detail(credentials: PlexCredentials, ratingKey: String): PlexMovie {
        val key = numericKey(ratingKey)
        val container = container(get(credentials, listOf("library", "metadata", key)))
        val metadata = container.optJSONArray("Metadata") ?: throw IOException("Plex 详情响应为空")
        return parseMovie(metadata.optJSONObject(0)) ?: throw IOException("Plex 详情字段无效")
    }

    /** Metadata only: the independently authorized WebDAV service will supply video bytes. */
    fun filePath(credentials: PlexCredentials, ref: PlexPlaybackRef, onCall: (okhttp3.Call) -> Unit = {}): String =
        fileLocation(credentials, ref, onCall).path

    fun fileLocation(credentials: PlexCredentials, ref: PlexPlaybackRef, onCall: (okhttp3.Call) -> Unit = {}): PlexFileLocation {
        try {
            val metadata = http.request(endpoint(credentialsBase(credentials), listOf("library", "metadata", numericKey(ref.ratingKey))),
                "GET", null, authHeaders(true, credentials.token, metadata = true), onCall = onCall)
            return selectFileLocation(metadata, ref.ratingKey, ref.partKey)
        } catch (error: PlexApiHttp.StatusException) {
            throw PlexPlaybackFailure("无法读取 Plex 文件资料，请检查 Plex 授权并刷新详情", httpStatus = error.code, cause = error)
        }
    }

    internal fun selectFilePath(json: JSONObject, ratingKey: String, partKey: String): String = selectFileLocation(json, ratingKey, partKey).path

    internal fun selectFileLocation(json: JSONObject, ratingKey: String, partKey: String): PlexFileLocation {
        val selection = selectPlaybackPart(json, ratingKey, partKey)
        val items = container(json).getJSONArray("Metadata")
        val item = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.first { it.optString("ratingKey") == ratingKey }
        val part = item.getJSONArray("Media").getJSONObject(selection.mediaIndex).getJSONArray("Part").getJSONObject(selection.partIndex)
        val path = part.optString("file").takeIf { it.isNotBlank() && it != "null" }
            ?: throw PlexPlaybackFailure("Plex 未提供视频文件位置，请检查资料权限并刷新影片详情")
        return PlexFileLocation(path, part.optLong("size", -1L).takeIf { it > 0 } ?: -1L)
    }

    /** A normal direct-play decision, using the exact media/part selected in the library. */
    fun directPlayDecision(credentials: PlexCredentials, ref: PlexPlaybackRef, onCall: (okhttp3.Call) -> Unit = {}): PlexPlaybackDecision {
        val key = numericKey(ref.ratingKey)
        try {
            val metadata = http.request(endpoint(credentialsBase(credentials), listOf("library", "metadata", key)), "GET", null,
                authHeaders(true, credentials.token, metadata = true), onCall = onCall)
            val selection = selectPlaybackPart(metadata, key, ref.partKey)
            val response = http.request(decisionUrl(credentials, key, selection), "GET", null,
                authHeaders(true, credentials.token, includeSession = true) + ("X-Plex-Client-Profile-Name" to "generic"), onCall = onCall)
            return PlexPlaybackDecision.fromJson(response, key, selection)
        } catch (error: PlexApiHttp.StatusException) {
            if (error.code in setOf(401, 403, 404)) {
                val message = when (error.code) {
                    401 -> "Plex 播放授权无效，请在设置中重新验证连接"
                    403 -> "Plex 服务器拒绝播放协商请求"
                    else -> "Plex 未提供所选影片或播放协商接口"
                }
                throw PlexPlaybackFailure(message, httpStatus = error.code, cause = error)
            }
            throw error
        }
    }

    internal fun decisionUrl(credentials: PlexCredentials, ratingKey: String, selection: PlexMediaSelection): HttpUrl =
        endpoint(credentialsBase(credentials), listOf("video", ":", "transcode", "universal", "decision")).newBuilder()
            .addQueryParameter("path", "/library/metadata/${numericKey(ratingKey)}")
            .addQueryParameter("mediaIndex", selection.mediaIndex.toString())
            .addQueryParameter("partIndex", selection.partIndex.toString())
            .addQueryParameter("directPlay", "1").addQueryParameter("directStream", "0")
            .addQueryParameter("directStreamAudio", "0").addQueryParameter("hasMDE", "1")
            .addQueryParameter("protocol", "http").addQueryParameter("subtitles", "none")
            .addQueryParameter("transcodeSessionId", sessionIdentifier).build()

    internal fun selectPlaybackPart(json: JSONObject, ratingKey: String, partKey: String): PlexMediaSelection {
        val metadata = container(json).optJSONArray("Metadata") ?: throw PlexPlaybackFailure("Plex 未返回所选影片资料")
        for (itemIndex in 0 until metadata.length()) {
            val item = metadata.optJSONObject(itemIndex) ?: continue
            if (item.optString("ratingKey") != ratingKey) continue
            val media = item.optJSONArray("Media") ?: continue
            for (mediaIndex in 0 until media.length()) {
                val parts = media.optJSONObject(mediaIndex)?.optJSONArray("Part") ?: continue
                for (partIndex in 0 until parts.length()) {
                    if (parts.optJSONObject(partIndex)?.optString("key") == partKey)
                        return PlexMediaSelection(mediaIndex, partIndex, item.optLong("duration", 0L))
                }
            }
        }
        throw PlexPlaybackFailure("Plex 中所选视频文件已改变，请返回详情页重新选择")
    }

    /** Server-owned Continue Watching hub; does not scan the library. */
    fun continueWatching(credentials: PlexCredentials, count: Int = 50): List<PlexMovie> {
        require(count in 1..100) { "Plex 继续观看数量无效" }
        val url = endpoint(credentialsBase(credentials), listOf("hubs", "continueWatching"))
            .newBuilder().addQueryParameter("count", count.toString()).build()
        val container = container(http.request(url, "GET", null, authHeaders(true, credentials.token, metadata = true)))
        val result = LinkedHashMap<String, PlexMovie>()
        val hubs = container.optJSONArray("Hub")
        if (hubs != null) for (i in 0 until hubs.length()) {
            val metadata = hubs.optJSONObject(i)?.optJSONArray("Metadata") ?: continue
            for (j in 0 until metadata.length()) PlexMetadataParser.parseMovie(metadata.optJSONObject(j))?.let { result.putIfAbsent(it.ratingKey, it) }
        }
        val direct = container.optJSONArray("Metadata")
        if (direct != null) for (i in 0 until direct.length()) PlexMetadataParser.parseMovie(direct.optJSONObject(i))?.let { result.putIfAbsent(it.ratingKey, it) }
        return result.values.toList()
    }

    fun imageBytes(credentials: PlexCredentials, path: String, portrait: Boolean = false): ByteArray {
        val url = imageUrl(credentialsBase(credentials), path, portrait)
        val response = http.raw(url, "GET", authHeaders(false, credentials.token), emptyMap())
        response.use { checked ->
            if (!checked.isSuccessful) throw PlexApiHttp.httpFailure(checked.code)
            val body = checked.body ?: throw IOException("Plex 图片为空")
            if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("Plex 图片过大")
            val input = body.byteStream()
            val output = ByteArrayOutputStream(minOf(body.contentLength().coerceAtLeast(0L), MAX_IMAGE_BYTES.toLong()).toInt())
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) throw IOException("Plex 图片过大")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    fun reportTimeline(credentials: PlexCredentials, ratingKey: String, state: String, positionMs: Long, durationMs: Long) {
        val key = numericKey(ratingKey)
        if (state !in VALID_STATES || positionMs < 0 || durationMs < 0) throw IllegalArgumentException("Plex 播放进度参数无效")
        val url = endpoint(credentialsBase(credentials), listOf(":", "timeline")).newBuilder()
            .addQueryParameter("key", "/library/metadata/$key").addQueryParameter("ratingKey", key)
            .addQueryParameter("state", state).addQueryParameter("time", positionMs.toString()).addQueryParameter("duration", durationMs.toString()).build()
        http.raw(url, "POST", authHeaders(false, credentials.token, includeSession = true), emptyMap()).use { if (!it.isSuccessful) throw PlexApiHttp.httpFailure(it.code) }
    }

    fun markWatched(credentials: PlexCredentials, ratingKey: String) {
        val key = numericKey(ratingKey)
        val url = endpoint(credentialsBase(credentials), listOf(":", "scrobble")).newBuilder()
            .addQueryParameter("identifier", "com.plexapp.plugins.library").addQueryParameter("key", key).build()
        http.raw(url, "PUT", authHeaders(false, credentials.token), emptyMap()).use { if (!it.isSuccessful) throw PlexApiHttp.httpFailure(it.code) }
    }

    fun markUnwatched(credentials: PlexCredentials, ratingKey: String) {
        val key = numericKey(ratingKey)
        val url = endpoint(credentialsBase(credentials), listOf(":", "unscrobble")).newBuilder()
            .addQueryParameter("identifier", "com.plexapp.plugins.library").addQueryParameter("key", key).build()
        http.raw(url, "PUT", authHeaders(false, credentials.token), emptyMap()).use { if (!it.isSuccessful) throw PlexApiHttp.httpFailure(it.code) }
    }

    private fun get(credentials: PlexCredentials, segments: List<String>): JSONObject = http.request(endpoint(credentialsBase(credentials), segments), "GET", null, authHeaders(true, credentials.token, metadata = true))

    private fun parseMovie(item: JSONObject?): PlexMovie? {
        return PlexMetadataParser.parseMovie(item)
    }

    private fun parseConnections(array: JSONArray): List<PlexConnection> = (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val address = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PlexConnection(address, item.optBoolean("local"), item.optBoolean("relay"))
    }

    private fun container(json: JSONObject): JSONObject = json.optJSONObject("MediaContainer") ?: json
    private fun numericKey(value: String): String = value.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: throw IllegalArgumentException("Plex 媒体编号无效")
    private fun requireToken(token: String) { if (token.isBlank()) throw IllegalArgumentException("Plex 授权令牌为空") }
    private fun credentialsBase(credentials: PlexCredentials): HttpUrl = validateServerAddress(credentials.config.address)
    private fun authHeaders(json: Boolean, token: String? = null, includeSession: Boolean = false, metadata: Boolean = false): Map<String, String> = buildMap { put("X-Plex-Product", "NASPlayer"); put("X-Plex-Client-Identifier", clientIdentifier); put("X-Plex-Platform", "Android"); put("X-Plex-Pms-Api-Version", "1.0"); if (metadata) put("X-Plex-Language", "zh-CN"); if (json) put("Accept", "application/json"); if (!token.isNullOrBlank()) put("X-Plex-Token", token); if (includeSession) put("X-Plex-Session-Identifier", sessionIdentifier) }

    companion object {
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private val VALID_STATES = setOf("stopped", "buffering", "playing", "paused")
        fun validateServerAddress(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Plex 服务器地址无效")
            if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) throw IllegalArgumentException("Plex 服务器地址格式不受支持")
            return url
        }
        fun endpoint(base: HttpUrl, segments: List<String>): HttpUrl { val builder = base.newBuilder(); segments.forEach { builder.addPathSegment(it) }; return builder.build() }
        fun imageUrl(base: HttpUrl, path: String, portrait: Boolean = false): HttpUrl {
            val uri = runCatching { URI(path) }.getOrNull() ?: throw IllegalArgumentException("Plex 图片路径无效")
            if (uri.isAbsolute) {
                if (uri.scheme.lowercase() != "https" || uri.userInfo != null || uri.fragment != null || hasCredentialQuery(uri.rawQuery)) throw IllegalArgumentException("Plex 外部头像地址无效")
                return base.newBuilder().encodedPath(withBasePath(base, "/photo/:/transcode")).addQueryParameter("url", uri.toString())
                    .addQueryParameter("width", if (portrait) "512" else "180").addQueryParameter("height", if (portrait) "768" else "270").addQueryParameter("format", "jpg").build()
            }
            if (uri.userInfo != null || uri.fragment != null || !uri.rawPath.startsWith("/library/metadata/") && !uri.rawPath.startsWith("/library/people/") && !uri.rawPath.startsWith("/people/") && !uri.rawPath.startsWith("/photo/:/transcode")) throw IllegalArgumentException("Plex 图片路径无效")
            if (uri.path.split('/').any { it == "." || it == ".." }) throw IllegalArgumentException("Plex 图片路径无效")
            val result = base.newBuilder().encodedPath(withBasePath(base, uri.rawPath)).apply { uri.rawQuery?.let { encodedQuery(it) } }.build()
            if (result.pathSegments.any { it == "." || it == ".." }) throw IllegalArgumentException("Plex 图片路径无效")
            return if (portrait && uri.rawPath.startsWith("/photo/:/transcode")) result.newBuilder()
                .setQueryParameter("width", "512").setQueryParameter("height", "768").build() else result
        }
        fun withBasePath(base: HttpUrl, path: String): String = base.encodedPath.trimEnd('/').let { prefix -> if (prefix.isEmpty()) path else prefix + path }
        fun hasCredentialQuery(query: String?): Boolean = query.orEmpty().split('&').map { it.substringBefore('=').trim() }.any { it.equals("token", true) || it.equals("authToken", true) || it.equals("x-plex-token", true) }
        fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}

fun plexErrorMessage(error: Exception): String = when (error) {
    is PlexApiHttp.StatusException -> when (error.code) { 401, 403 -> "Plex 授权无效或已过期"; 404 -> "Plex 资源不存在"; 408, 504 -> "Plex 请求超时"; else -> "Plex 服务暂时不可用（HTTP ${error.code}）" }
    is java.net.SocketTimeoutException -> "Plex 请求超时"
    is UnknownHostException -> "Plex 服务器地址无法解析"
    is ConnectException -> "无法连接 Plex 服务器"
    is SSLException -> "Plex TLS 连接失败"
    is IllegalArgumentException -> "Plex 参数无效"
    else -> "Plex 请求失败"
}

private class PlexApiHttp(private val clientIdentifier: String) {
    class StatusException(val code: Int) : IOException("Plex HTTP $code")
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()
    fun request(url: HttpUrl, method: String, body: okhttp3.RequestBody?, extra: Map<String, String>, requestHeaders: Map<String, String> = emptyMap(), onCall: (okhttp3.Call) -> Unit = {}): JSONObject {
        val started = System.nanoTime()
        var status: Int? = null
        var failure: Exception? = null
        try {
            val response = raw(url, method, extra, requestHeaders, body, onCall)
            response.use { checked ->
                status = checked.code
                if (!checked.isSuccessful) throw httpFailure(checked.code)
                val bytes = readLimited(checked.body?.byteStream() ?: throw IOException("Plex 返回为空"), MAX_RESPONSE_BYTES)
                return JSONObject(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            PlexRequestDiagnostics.record(operation(url), started, status, failure)
        }
    }
    private fun operation(url: HttpUrl): String {
        val path = url.encodedPath
        return when {
            path.endsWith("/transcode/universal/decision") -> "播放协商"
            path.endsWith("/allLeaves") -> "整剧集数"
            path.endsWith("/children") -> "季与集"
            path.contains("/library/metadata/") -> "影片详情"
            path.contains("/library/sections/") -> "库内列表"
            path.endsWith("/library/sections") -> "媒体库"
            path.contains("/hubs/") -> "继续观看"
            path.endsWith("/identity") -> "服务器连接"
            else -> "其他接口"
        }
    }
    fun requestArray(url: HttpUrl, extra: Map<String, String>): JSONArray { val response = raw(url, "GET", extra, emptyMap()); response.use { checked -> if (!checked.isSuccessful) throw httpFailure(checked.code); return JSONArray(String(readLimited(checked.body?.byteStream() ?: throw IOException("Plex 返回为空"), MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)) } }
    fun raw(url: HttpUrl, method: String, extra: Map<String, String>, requestHeaders: Map<String, String>, body: okhttp3.RequestBody? = null, onCall: (okhttp3.Call) -> Unit = {}): Response {
        val actualBody = body ?: if (method == "GET" || method == "HEAD") null else "".toRequestBody("application/octet-stream".toMediaType())
        val builder = Request.Builder().url(url).method(method, actualBody)
        (extra + requestHeaders).forEach { builder.header(it.key, it.value) }
        val call = client.newCall(builder.build())
        onCall(call)
        val response = call.execute()
        if (response.code in 300..399) { response.close(); throw IOException("Plex 重定向不受支持") }
        return response
    }
    companion object { private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024; fun readLimited(input: InputStream, limit: Int): ByteArray { val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0; while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > limit) throw IOException("Plex 响应过大"); output.write(buffer, 0, count) }; return output.toByteArray() }; fun httpFailure(code: Int): StatusException = StatusException(code) }
}
