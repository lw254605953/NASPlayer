package com.megatron.player.plex

import android.net.Uri

data class PlexServerConfig(val id: String, val machineIdentifier: String, val name: String, val address: String)
class PlexCredentials(val config: PlexServerConfig, val token: String)
data class PlexConnection(val address: String, val local: Boolean, val relay: Boolean)
class PlexServerOption(val machineIdentifier: String, val name: String, val token: String, val connections: List<PlexConnection>)
data class PlexPin(val id: Int, val code: String, val authUrl: String, val expiresInSeconds: Int)
data class PlexLibrary(val key: String, val title: String, val type: String, val locations: List<String> = emptyList())
data class PlexPart(val key: String, val container: String, val videoCodec: String, val audioCodec: String, val width: Int, val height: Int, val filePath: String = "", val sizeBytes: Long = 0L)
data class PlexPerson(val name: String, val role: String = "", val thumb: String = "")
data class PlexRating(val label: String, val value: Double, val source: String = "")
data class PlexMovie(
    val ratingKey: String,
    val title: String,
    val year: Int = 0,
    val summary: String = "",
    val thumb: String = "",
    val art: String = "",
    val rating: String = "",
    val durationMs: Long = 0,
    val viewOffsetMs: Long = 0,
    val watched: Boolean = false,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val parts: List<PlexPart> = emptyList(),
    val cast: List<PlexPerson> = emptyList(),
    val ratings: List<PlexRating> = emptyList(),
    val originalTitle: String = "",
    val tagline: String = "",
    val studio: String = "",
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val releaseDate: String = "",
    val addedAt: Long = 0,
    val lastViewedAt: Long = 0,
    val type: String = "movie",
    val index: Int = -1,
    val parentIndex: Int = -1,
    val parentRatingKey: String = "",
    val grandparentRatingKey: String = "",
    val parentTitle: String = "",
    val grandparentTitle: String = "",
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    val childCount: Int = 0,
    /** Local playback intent only; never serialized into Plex metadata caches. */
    val playFromStart: Boolean = false,
)
data class PlexMoviePage(val movies: List<PlexMovie>, val total: Int, val offset: Int)
data class PlexPlaybackRef(val serverId: String, val ratingKey: String, val partKey: String)

object PlexUris {
    fun build(serverId: String, ratingKey: String, partKey: String): Uri = Uri.Builder()
        .scheme("mpplex").authority(serverId).appendPath(ratingKey).appendQueryParameter("part", partKey).build()

    fun parse(uri: Uri): PlexPlaybackRef? {
        if (uri.scheme != "mpplex") return null
        val serverId = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val ratingKey = uri.pathSegments.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val partKey = uri.getQueryParameter("part")?.takeIf { it.startsWith("/library/parts/") } ?: return null
        return PlexPlaybackRef(serverId, ratingKey, partKey)
    }
}
