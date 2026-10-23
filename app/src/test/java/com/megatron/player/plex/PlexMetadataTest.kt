package com.megatron.player.plex

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexMetadataTest {
    @Test fun actorPortraitRequestsMorePixelsWithoutChangingGenericPosters() {
        val base = "https://pms.example/plex/".toHttpUrl()
        val actor = PlexClient.imageUrl(base, "https://images.example/actor.jpg", portrait = true)
        assertEquals("512", actor.queryParameter("width"))
        assertEquals("768", actor.queryParameter("height"))
        assertEquals("180", PlexClient.imageUrl(base, "https://images.example/poster.jpg").queryParameter("width"))
        val existing = PlexClient.imageUrl(base, "/photo/:/transcode?width=100&height=150&url=%2Fpeople%2F1%2Fthumb", portrait = true)
        assertEquals("512", existing.queryParameter("width"))
        assertEquals("/people/1/thumb", existing.queryParameter("url"))
    }
    @Test fun audienceAndRoleKeepDisplayFields() {
        val movie = PlexMetadataParser.parseMovie(JSONObject("""
            {"ratingKey":"1","title":"片名","audienceRating":8.4,"Role":[{"tag":"演员","role":"角色","thumb":"/people/1/thumb"}]}
        """))!!
        assertEquals("8.4", movie.rating)
        assertEquals(PlexPerson("演员", "角色", "/people/1/thumb"), movie.cast.single())
        assertEquals("演员", movie.actors.single())
    }

    @Test fun ratingArrayUsesProviderTypeAndDeduplicatesNull() {
        val movie = PlexMetadataParser.parseMovie(JSONObject("""
            {"ratingKey":"1","Rating":[
              {"image":"rottentomatoes://image.rating.upright","type":"audience","value":8.0},
              {"image":"rottentomatoes://image.rating.upright","type":"audience","value":8.0},
              {"image":null,"type":"critic","value":null}
            ]}
        """))!!
        assertEquals("8", movie.rating)
        assertEquals(1, movie.ratings.size)
        assertEquals("rottentomatoes:audience", movie.ratings.single().source)
        assertTrue(movie.ratings.single().label.contains("烂番茄"))
    }

    @Test fun sortQueryUsesServerDirectionAndSearch() {
        val credentials = PlexCredentials(PlexServerConfig("id", "machine", "Plex", "https://pms.example/plex/"), "token")
        val url = PlexClient("client").movieListUrl(credentials, "7", "星际", PlexSort.RELEASED, true)
        assertEquals("originallyAvailableAt:desc", url.queryParameter("sort"))
        assertEquals("星际", url.queryParameter("title"))
    }

    @Test fun externalAvatarProxyStaysOnPmsWithoutToken() {
        val base = "https://pms.example/plex/".toHttpUrl()
        val url = PlexClient.imageUrl(base, "https://images.example/a.jpg?sig=abc")
        assertEquals("pms.example", url.host)
        assertEquals("/plex/photo/:/transcode", url.encodedPath)
        assertEquals("https://images.example/a.jpg?sig=abc", url.queryParameter("url"))
        assertEquals(null, url.queryParameter("X-Plex-Token"))
        assertEquals("180", url.queryParameter("width"))
        assertEquals("270", url.queryParameter("height"))
    }
}
