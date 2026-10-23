package com.megatron.player.plex

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal object PlexMetadataParser {
    fun parseMovie(item: JSONObject?): PlexMovie? {
        item ?: return null
        val ratingKey = text(item, "ratingKey") ?: return null
        val audience = ratingNumber(item, "audienceRating")
        val critic = ratingNumber(item, "rating")
        val user = ratingNumber(item, "userRating")
        val scalarRatings = buildList {
            audience?.let { val provider = providerFromImage(text(item, "audienceRatingImage") ?: ""); add(PlexRating(labelFor(provider, "audience"), it, "$provider:audience")) }
            critic?.let { val provider = providerFromImage(text(item, "ratingImage") ?: ""); add(PlexRating(labelFor(provider, "critic"), it, "$provider:critic")) }
            user?.let { add(PlexRating(labelFor("", "user"), it, "plex:user")) }
        }
        val arrayRatings = item.optJSONArray("Rating")?.let(::parseRatingArray) ?: emptyList()
        val ratings = (scalarRatings + arrayRatings).distinctBy { "${it.source}:${it.value}" }
        val rating = (arrayRatings.firstOrNull { it.source.endsWith(":audience") } ?: scalarRatings.firstOrNull { it.source.endsWith(":audience") }
            ?: arrayRatings.firstOrNull { it.source.endsWith(":critic") } ?: scalarRatings.firstOrNull { it.source.endsWith(":critic") }
            ?: arrayRatings.firstOrNull { it.source.endsWith(":user") } ?: scalarRatings.firstOrNull { it.source.endsWith(":user") })?.value
        val people = parsePeople(item.optJSONArray("Role"))
        val media = item.optJSONArray("Media")
        val type = text(item, "type") ?: "movie"
        val leafCount = number(item, "leafCount")?.toInt() ?: 0
        val viewedLeafCount = number(item, "viewedLeafCount")?.toInt() ?: 0
        // Container viewCount can be set after watching only one episode.
        val watched = if (type == "show" || type == "season") {
            leafCount > 0 && viewedLeafCount >= leafCount
        } else number(item, "viewCount")?.let { it > 0 } ?: false
        return PlexMovie(
            ratingKey = ratingKey,
            title = text(item, "title") ?: ratingKey,
            year = number(item, "year")?.toInt() ?: 0,
            summary = text(item, "summary") ?: "",
            thumb = text(item, "thumb") ?: "",
            art = text(item, "art") ?: "",
            rating = rating?.let(::formatRating) ?: "",
            durationMs = number(item, "duration")?.toLong() ?: 0L,
            viewOffsetMs = number(item, "viewOffset")?.toLong() ?: 0L,
            watched = watched,
            genres = tags(item.optJSONArray("Genre")),
            actors = people.map { it.name },
            parts = (0 until (media?.length() ?: 0)).flatMap { parseParts(media?.optJSONObject(it)) }.distinctBy { it.key },
            cast = people,
            ratings = ratings,
            originalTitle = text(item, "originalTitle") ?: "",
            tagline = text(item, "tagline") ?: "",
            studio = text(item, "studio") ?: text(item, "studioTitle") ?: firstTag(item.optJSONArray("Studio")),
            directors = tags(item.optJSONArray("Director")),
            writers = tags(item.optJSONArray("Writer")),
            countries = tags(item.optJSONArray("Country")),
            releaseDate = text(item, "originallyAvailableAt") ?: "",
            addedAt = number(item, "addedAt")?.toLong() ?: 0L,
            lastViewedAt = number(item, "lastViewedAt")?.toLong() ?: 0L,
            type = type,
            index = number(item, "index")?.toInt() ?: -1,
            parentIndex = number(item, "parentIndex")?.toInt() ?: -1,
            parentRatingKey = text(item, "parentRatingKey") ?: "",
            grandparentRatingKey = text(item, "grandparentRatingKey") ?: "",
            parentTitle = text(item, "parentTitle") ?: "",
            grandparentTitle = text(item, "grandparentTitle") ?: "",
            leafCount = leafCount,
            viewedLeafCount = viewedLeafCount,
            childCount = number(item, "childCount")?.toInt() ?: 0,
        )
    }

    private fun parsePeople(array: JSONArray?): List<PlexPerson> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = text(item, "tag") ?: return@mapNotNull null
        PlexPerson(name, text(item, "role") ?: "", text(item, "thumb") ?: "")
    }

    private fun parseParts(media: JSONObject?): List<PlexPart> {
        val array = media?.optJSONArray("Part") ?: return emptyList()
        if (array.length() != 1) return emptyList()
        val part = array.optJSONObject(0) ?: return emptyList()
        val key = text(part, "key")?.takeIf { it.startsWith("/library/parts/") } ?: return emptyList()
        val uri = runCatching { URI(key) }.getOrNull() ?: return emptyList()
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || uri.path.split('/').any { it == "." || it == ".." }) return emptyList()
        return listOf(PlexPart(key, text(media, "container") ?: "", text(media, "videoCodec") ?: "", text(media, "audioCodec") ?: "", number(media, "width")?.toInt() ?: 0, number(media, "height")?.toInt() ?: 0, text(part, "file") ?: "", part.optLong("size", 0L).coerceAtLeast(0L)))
    }

    private fun parseRatingArray(array: JSONArray): List<PlexRating> = (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val value = ratingNumber(item, "value") ?: ratingNumber(item, "rating") ?: return@mapNotNull null
        val image = text(item, "image") ?: ""
        val type = when {
            text(item, "type")?.equals("audience", true) == true -> "audience"
            text(item, "type")?.equals("critic", true) == true -> "critic"
            image.contains("upright", true) || image.contains("audience", true) -> "audience"
            else -> "critic"
        }
        val provider = providerFromImage(image)
        PlexRating(labelFor(provider, type), value, "$provider:$type")
    }

    private fun tags(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { text(array.optJSONObject(it), "tag") }
    private fun firstTag(array: JSONArray?): String = tags(array).firstOrNull() ?: ""
    private fun text(item: JSONObject?, key: String): String? {
        if (item == null || !item.has(key) || item.isNull(key)) return null
        return item.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
    }
    private fun number(item: JSONObject?, key: String): Double? {
        if (item == null || !item.has(key) || item.isNull(key)) return null
        val value = item.opt(key)
        return when (value) { is Number -> value.toDouble(); is String -> value.toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() }
    }
    private fun ratingNumber(item: JSONObject?, key: String): Double? = number(item, key)?.takeIf { it > 0.0 && it <= 10.0 }
    private fun providerFromImage(image: String): String = when {
        image.contains("rottentomatoes", true) -> "rottentomatoes"
        image.contains("themoviedb", true) -> "themoviedb"
        image.contains("imdb", true) -> "imdb"
        image.contains("douban", true) -> "douban"
        image.isBlank() -> "plex"
        else -> "plex"
    }
    private fun labelFor(provider: String, type: String): String {
        val providerLabel = when (provider) { "rottentomatoes" -> "烂番茄"; "themoviedb" -> "TMDB"; "imdb" -> "IMDb"; "douban" -> "豆瓣"; else -> "" }
        val typeLabel = when (type) { "audience" -> "观众评分"; "user" -> "个人评分"; else -> "影评评分" }
        return if (providerLabel.isBlank()) typeLabel else "$providerLabel $typeLabel"
    }
    private fun formatRating(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
