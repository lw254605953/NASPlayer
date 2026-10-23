package com.megatron.player.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexEpisodeOrderTest {
    private fun episode(key: String, season: Int, number: Int, watched: Boolean = false, offset: Long = 0, last: Long = 0) =
        PlexMovie(key, "集$number", type = "episode", parentIndex = season, index = number,
            grandparentTitle = "测试剧", watched = watched, viewOffsetMs = offset, durationMs = 1000, lastViewedAt = last)

    @Test fun ordersSeasonsEpisodesAndRemovesDuplicates() {
        val result = orderedPlexEpisodes(listOf(episode("2", 1, 2), episode("1", 1, 1), episode("2", 1, 2), episode("0", 0, 1)))
        assertEquals(listOf("0", "1", "2"), result.map { it.ratingKey })
    }

    @Test fun resumesLatestUnfinishedThenFirstUnwatched() {
        val items = listOf(episode("a", 1, 1, watched = true), episode("b", 1, 2, offset = 200, last = 2), episode("c", 1, 3, offset = 100, last = 3))
        assertEquals("c", resumePlexEpisode(items)?.ratingKey)
        assertEquals("b", resumePlexEpisode(items.filter { it.ratingKey != "c" })?.ratingKey)
    }

    @Test fun specialDoesNotStealResumeAndOnlyMovesWithinSpecialSeason() {
        val special = episode("s", 0, 1, offset = 500, last = 99)
        val normal = episode("n", 1, 1)
        assertEquals("n", resumePlexEpisode(listOf(special, normal))?.ratingKey)
        assertNull(adjacentPlexEpisode(listOf(special, normal), "s", 1))
    }

    @Test fun adjacencyCrossesNormalSeasonsAndHonoursEnds() {
        val first = episode("a", 1, 1)
        val last = episode("b", 1, 2)
        val next = episode("c", 2, 1)
        val items = listOf(next, last, first)
        assertEquals("c", adjacentPlexEpisode(items, "b", 1)?.ratingKey)
        assertNull(adjacentPlexEpisode(items, "a", -1))
        assertNull(adjacentPlexEpisode(items, "c", 1))
    }
    @Test fun completedPositionDoesNotDisplaceNextUnwatchedEpisode() {
        assertEquals("b", resumePlexEpisode(listOf(episode("a", 1, 1, offset = 1000), episode("b", 1, 2)))?.ratingKey)
        assertEquals("b", resumePlexEpisode(listOf(episode("a", 1, 1, offset = 960), episode("b", 1, 2)))?.ratingKey)
    }

    @Test fun movieAndUnknownNumbersDoNotGetInventedEpisodeLabels() {
        assertEquals("电影", plexPlaybackTitle(PlexMovie("m", "电影")))
        assertEquals("未编号集", plexPlaybackTitle(PlexMovie("e", "未编号集", type = "episode")))
    }

}
