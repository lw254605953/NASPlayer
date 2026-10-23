package com.megatron.player.music.metadata

import android.content.Context
import com.megatron.player.BuildConfig
import com.megatron.player.music.MusicArtworkStore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.*
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MusicMatch(val recordingId: String, val releaseId: String, val title: String, val artist: String, val album: String,
                      val version: String, val durationMs: Long) {
    val id get() = "$recordingId:$releaseId"
    val source get() = "https://musicbrainz.org/recording/$recordingId"
    fun json() = JSONObject().put("recording", recordingId).put("release", releaseId).put("title", title).put("artist", artist)
        .put("album", album).put("version", version).put("duration", durationMs)
    companion object {
        fun read(j: JSONObject): MusicMatch {
            val r = j.getString("recording"); val a = j.optString("release")
            require(validMbid(r) && (a.isEmpty() || validMbid(a)))
            return MusicMatch(r, a, j.getString("title").take(512), j.optString("artist").take(512), j.optString("album").take(512), j.optString("version").take(256), j.optLong("duration").coerceAtLeast(0))
        }
    }
}
internal fun validMbid(id: String) = id.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
internal fun musicClue(filename: String): String? {
    val name = filename.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().take(240)
    if (!name.any { it.isLetter() } || name.matches(Regex("(?i)(track|audio|歌曲|音轨)[ _-]*\\d+")) || name.matches(Regex("(?i)[a-f0-9]{24,}"))) return null
    if (Regex("(?i)(https?://|password|token[=:]|sk-[a-z0-9]|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b)").containsMatchIn(name)) return null
    return name
}
internal fun luceneMusic(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").take(240)

/** Public catalogue calls carry no NAS/Plex auth; model-returned URLs are never used. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class MusicCatalog(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(25, TimeUnit.SECONDS).connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }
    private suspend fun bytes(address: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        var url = address.toHttpUrl()
        repeat(4) {
            require(allowedCatalogUrl(url)) { "资料来源地址不受支持" }
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "NASPlayer/${BuildConfig.VERSION_NAME} (Android music metadata)").build())
            active = call
            val cancel = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
            try { call.execute().use { response ->
                if (response.code in 300..399) { url = response.header("Location")?.let { url.resolve(it) } ?: throw IOException("资料服务重定向失败") }
                else {
                    if (!response.isSuccessful) throw IOException(when (response.code) { 429 -> "资料服务请求过多，请稍后重试"; 404 -> "未找到对应资料"; else -> "资料服务请求失败（HTTP ${response.code}）" })
                    val body = response.body ?: throw IOException("资料服务返回空内容")
                    require(body.contentLength() <= limit) { "资料内容超过大小上限" }
                    val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input -> val buffer = ByteArray(8192); while (true) { val size = input.read(buffer); if (size < 0) break; require(out.size() + size <= limit) { "资料内容超过大小上限" }; out.write(buffer, 0, size) } }
                    return@withContext out.toByteArray()
                }
            } } finally { cancel.dispose(); if (active === call) active = null }
        }
        throw IOException("资料服务重定向次数过多")
    }
    suspend fun search(title: String, artist: String): List<MusicMatch> {
        synchronized(rateLock) { /* Rate delay is enforced below without holding a blocking lock. */ }
        rateMutex.lock()
        try { val wait = (lastCall + 1100 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0); delay(wait); lastCall = android.os.SystemClock.elapsedRealtime() }
        finally { rateMutex.unlock() }
        val query = "recording:\"${luceneMusic(title)}\"" + if (artist.isBlank()) "" else " AND artist:\"${luceneMusic(artist)}\""
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("fmt", "json").addQueryParameter("limit", "6").build()
        val j = JSONObject(String(bytes(url.toString(), 1024 * 1024), Charsets.UTF_8)); val rows = j.optJSONArray("recordings") ?: JSONArray()
        return buildList {
            for (i in 0 until minOf(rows.length(), 6)) {
                val row = rows.getJSONObject(i); val rid = row.optString("id"); if (!validMbid(rid)) continue
                val credits = row.optJSONArray("artist-credit") ?: JSONArray()
                val credit = (0 until credits.length()).joinToString("") { k -> credits.optJSONObject(k)?.let { it.optString("name", it.optJSONObject("artist")?.optString("name").orEmpty()) + it.optString("joinphrase") }.orEmpty() }.take(512)
                val releases = row.optJSONArray("releases") ?: JSONArray()
                if (releases.length() == 0) add(MusicMatch(rid, "", row.optString("title").take(512), credit, "", row.optString("disambiguation").take(256), row.optLong("length")))
                for (k in 0 until minOf(releases.length(), 3)) {
                    val release = releases.getJSONObject(k); val aid = release.optString("id"); if (!validMbid(aid)) continue
                    val version = listOf(row.optString("disambiguation"), release.optString("date"), release.optString("country"), release.optString("disambiguation")).filter(String::isNotBlank).joinToString(" · ").take(256)
                    add(MusicMatch(rid, aid, row.optString("title").take(512), credit, release.optString("title").take(512), version, row.optLong("length")))
                }
            }
        }.distinctBy { it.id }.take(12)
    }
    suspend fun lyrics(match: MusicMatch, durationMs: Long): String? {
        if (match.artist.isBlank() || match.album.isBlank() || durationMs <= 0) return null
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder().addQueryParameter("track_name", match.title).addQueryParameter("artist_name", match.artist)
            .addQueryParameter("album_name", match.album).addQueryParameter("duration", (durationMs / 1000.0).toString()).build()
        return try {
            val j = JSONObject(String(bytes(url.toString(), 256 * 1024), Charsets.UTF_8))
            if (kotlin.math.abs(j.optDouble("duration", -1.0) * 1000 - durationMs) > 2500) return null
            val text = listOf("syncedLyrics", "plainLyrics").map { if (j.isNull(it)) "" else j.optString(it) }.firstOrNull(String::isNotBlank) ?: return null
            text.takeIf { it.length <= 32_768 }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    suspend fun artwork(match: MusicMatch): String? {
        if (match.releaseId.isBlank()) return null
        return try { val image = bytes("https://coverartarchive.org/release/${match.releaseId}/front-500", 4 * 1024 * 1024)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            if (bounds.outWidth.toLong() * bounds.outHeight !in 1..16_000_000L) null else MusicArtworkStore(context).save(image)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    companion object {
        private val rateLock = Any(); private val rateMutex = kotlinx.coroutines.sync.Mutex(); private var lastCall = 0L
        fun allowedCatalogUrl(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (url.host in setOf("musicbrainz.org", "coverartarchive.org", "lrclib.net", "archive.org") || url.host.endsWith(".archive.org"))
    }
}
