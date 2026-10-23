package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexSeriesTest {
    @Test fun parserKeepsSeasonZeroAndParentFields() {
        val episode = PlexMetadataParser.parseMovie(JSONObject("""
            {"ratingKey":"e0","type":"episode","title":"特别篇","index":0,"parentIndex":0,
             "parentRatingKey":"s0","grandparentRatingKey":"show","parentTitle":"特别篇",
             "grandparentTitle":"剧集","duration":1000,"viewOffset":100,"viewCount":0}
        """))!!
        assertEquals("episode", episode.type)
        assertEquals(0, episode.index)
        assertEquals(0, episode.parentIndex)
        assertEquals("s0", episode.parentRatingKey)
        assertEquals("show", episode.grandparentRatingKey)
        assertEquals(1000L, episode.durationMs)
    }

    @Test fun parserMapsSeriesCountsWithoutInventingMissingValues() {
        val show = PlexMetadataParser.parseMovie(JSONObject("""{"ratingKey":"show","type":"show","leafCount":3,"viewedLeafCount":1,"childCount":2}"""))!!
        assertEquals(3, show.leafCount)
        assertEquals(1, show.viewedLeafCount)
        assertEquals(2, show.childCount)
        assertEquals(-1, show.index)
        assertTrue(show.rating.isEmpty())
    }
}
