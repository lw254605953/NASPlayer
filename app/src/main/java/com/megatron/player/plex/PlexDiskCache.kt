package com.megatron.player.plex

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Small private, bounded JSON cache. Cache names contain hashes only, never URLs or tokens. */
internal class PlexDiskCache(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "plex_metadata_v1")
    private val maxFiles = 96
    private val maxBytes = 8L * 1024L * 1024L
    private val maxAgeMs = 24L * 60L * 60L * 1000L

    fun identity(credentials: PlexCredentials): String = identityFor(credentials)

    @Synchronized fun read(identity: String, kind: String, key: String): JSONObject? {
        return runCatching {
            val file = file(identity, kind, key)
            if (!file.isFile || System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
                file.delete()
                null
            } else JSONObject(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    @Synchronized fun write(identity: String, kind: String, key: String, value: JSONObject) {
        val directory = directory(identity)
        if (!directory.exists()) directory.mkdirs()
        val target = file(identity, kind, key)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(value.toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        trim()
    }

    @Synchronized fun clearIdentity(identity: String) { directory(identity).deleteRecursively() }
    @Synchronized fun invalidatePlayback(identity: String, ratingKey: String, showKey: String? = null, seasonKey: String? = null) {
        val directory = directory(identity)
        deleteKey(directory, "detail", ratingKey)
        showKey?.takeIf { it.isNotBlank() }?.let {
            deleteKey(directory, "detail", it)
            deleteKey(directory, "episodes", it)
            deleteKey(directory, "seasons", it)
        }
        seasonKey?.takeIf { it.isNotBlank() }?.let { deleteKey(directory, "detail", it) }
        directory.listFiles()?.filter { file -> file.isFile && (file.name.startsWith("page-") || file.name.startsWith("continue-")) }?.forEach(File::delete)
    }
    @Synchronized fun clearAll() { root.deleteRecursively() }

    private fun file(identity: String, kind: String, key: String): File =
        File(directory(identity), "${safe(kind)}-${digest(key)}.json")

    private fun deleteKey(directory: File, kind: String, key: String) {
        File(directory, "${safe(kind)}-${digest(key)}.json").delete()
    }

    private fun directory(identity: String): File = File(root, digest(identity))
    private fun safe(value: String): String = value.filter { it.isLetterOrDigit() || it == '_' }.take(32).ifBlank { "data" }

    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList().sortedBy { it.lastModified() }.toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.size > maxFiles || bytes > maxBytes) {
            val old = files.removeFirstOrNull() ?: break
            bytes -= old.length()
            old.delete()
        }
    }

    companion object {
        fun identityFor(credentials: PlexCredentials): String = digest(
            listOf(credentials.config.id, credentials.config.machineIdentifier, credentials.config.address, digest(credentials.token)).joinToString("|")
        )
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun movies(value: JSONArray): List<PlexMovie> = (0 until value.length()).mapNotNull { PlexMovieCodec.fromJson(value.optJSONObject(it)) }
        fun movies(value: List<PlexMovie>): JSONArray = JSONArray().apply { value.forEach { put(PlexMovieCodec.toJson(it)) } }
    }
}

internal object PlexMovieCodec {
    fun toJson(movie: PlexMovie): JSONObject = JSONObject().apply {
        put("ratingKey", movie.ratingKey); put("title", movie.title); put("year", movie.year)
        put("summary", movie.summary); put("thumb", movie.thumb); put("art", movie.art); put("rating", movie.rating)
        put("durationMs", movie.durationMs); put("viewOffsetMs", movie.viewOffsetMs); put("watched", movie.watched)
        put("genres", JSONArray(movie.genres)); put("actors", JSONArray(movie.actors)); put("cast", JSONArray().also { a -> movie.cast.forEach { a.put(JSONObject().put("name", it.name).put("role", it.role).put("thumb", it.thumb)) } })
        put("ratings", JSONArray().also { a -> movie.ratings.forEach { a.put(JSONObject().put("label", it.label).put("value", it.value).put("source", it.source)) } })
        put("parts", JSONArray().also { a -> movie.parts.forEach { a.put(JSONObject().put("key", it.key).put("container", it.container).put("videoCodec", it.videoCodec).put("audioCodec", it.audioCodec).put("width", it.width).put("height", it.height).put("filePath", it.filePath).put("sizeBytes", it.sizeBytes)) } })
        put("originalTitle", movie.originalTitle); put("tagline", movie.tagline); put("studio", movie.studio)
        put("directors", JSONArray(movie.directors)); put("writers", JSONArray(movie.writers)); put("countries", JSONArray(movie.countries))
        put("releaseDate", movie.releaseDate); put("addedAt", movie.addedAt); put("lastViewedAt", movie.lastViewedAt)
        put("type", movie.type); put("index", movie.index); put("parentIndex", movie.parentIndex); put("parentRatingKey", movie.parentRatingKey)
        put("grandparentRatingKey", movie.grandparentRatingKey); put("parentTitle", movie.parentTitle); put("grandparentTitle", movie.grandparentTitle)
        put("leafCount", movie.leafCount); put("viewedLeafCount", movie.viewedLeafCount); put("childCount", movie.childCount)
    }

    fun fromJson(item: JSONObject?): PlexMovie? {
        item ?: return null
        return PlexMovie(
            ratingKey = item.optString("ratingKey").takeIf { it.isNotBlank() } ?: return null,
            title = item.optString("title"), year = item.optInt("year"), summary = item.optString("summary"), thumb = item.optString("thumb"), art = item.optString("art"), rating = item.optString("rating"),
            durationMs = item.optLong("durationMs"), viewOffsetMs = item.optLong("viewOffsetMs"), watched = item.optBoolean("watched"), genres = strings(item.optJSONArray("genres")), actors = strings(item.optJSONArray("actors")),
            parts = (0 until item.optJSONArray("parts").orEmptyLength()).mapNotNull { i -> item.optJSONArray("parts")?.optJSONObject(i)?.let { PlexPart(it.optString("key"), it.optString("container"), it.optString("videoCodec"), it.optString("audioCodec"), it.optInt("width"), it.optInt("height"), it.optString("filePath"), it.optLong("sizeBytes")) } },
            cast = (0 until item.optJSONArray("cast").orEmptyLength()).mapNotNull { i -> item.optJSONArray("cast")?.optJSONObject(i)?.let { PlexPerson(it.optString("name"), it.optString("role"), it.optString("thumb")) } },
            ratings = (0 until item.optJSONArray("ratings").orEmptyLength()).mapNotNull { i -> item.optJSONArray("ratings")?.optJSONObject(i)?.let { PlexRating(it.optString("label"), it.optDouble("value"), it.optString("source")) } },
            originalTitle = item.optString("originalTitle"), tagline = item.optString("tagline"), studio = item.optString("studio"), directors = strings(item.optJSONArray("directors")), writers = strings(item.optJSONArray("writers")), countries = strings(item.optJSONArray("countries")), releaseDate = item.optString("releaseDate"), addedAt = item.optLong("addedAt"), lastViewedAt = item.optLong("lastViewedAt"), type = item.optString("type", "movie"), index = item.optInt("index", -1), parentIndex = item.optInt("parentIndex", -1), parentRatingKey = item.optString("parentRatingKey"), grandparentRatingKey = item.optString("grandparentRatingKey"), parentTitle = item.optString("parentTitle"), grandparentTitle = item.optString("grandparentTitle"), leafCount = item.optInt("leafCount"), viewedLeafCount = item.optInt("viewedLeafCount"), childCount = item.optInt("childCount")
        )
    }

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    private fun JSONArray?.orEmptyLength(): Int = this?.length() ?: 0
}
