package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexCacheCodecTest {
    @Test fun cacheScopeKeepsRenamesButSeparatesCredentialsAndServers() {
        val config = PlexServerConfig("id", "machine", "名称", "http://localhost:8791/")
        val first = PlexDiskCache.identityFor(PlexCredentials(config, "test-token-a"))
        assertEquals(first, PlexDiskCache.identityFor(PlexCredentials(config.copy(name = "新名称"), "test-token-a")))
        assertNotEquals(first, PlexDiskCache.identityFor(PlexCredentials(config, "test-token-b")))
        assertNotEquals(first, PlexDiskCache.identityFor(PlexCredentials(config.copy(address = "http://localhost:8792/"), "test-token-a")))
        assertNotEquals(first, PlexDiskCache.identityFor(PlexCredentials(config.copy(machineIdentifier = "other"), "test-token-a")))
    }

    @Test fun cacheRoundTripKeepsPlaybackPeopleRatingsAndHierarchy() {
        val movie = PlexMovie("211", "故事", year = 2026, summary = "简介", thumb = "/thumb", art = "/art", rating = "8.5",
            durationMs = 120000, viewOffsetMs = 30000, watched = false, genres = listOf("剧情"), actors = listOf("演员"),
            parts = listOf(PlexPart("/library/parts/211/1/a.mkv", "mkv", "hevc", "eac3", 1920, 1080)),
            cast = listOf(PlexPerson("演员", "角色", "/person")), ratings = listOf(PlexRating("观众", 8.5, "plex:audience")),
            directors = listOf("导演"), countries = listOf("中国"), releaseDate = "2026-10-01", lastViewedAt = 100,
            type = "episode", index = 1, parentIndex = 1, parentRatingKey = "210", grandparentRatingKey = "200", grandparentTitle = "剧集", leafCount = 4, viewedLeafCount = 1)
        assertEquals(movie, PlexMovieCodec.fromJson(PlexMovieCodec.toJson(movie)))
    }

    @Test fun unknownMetadataDoesNotBecomeAPlayableCacheEntry() {
        assertNull(PlexMovieCodec.fromJson(JSONObject()))
    }
}
