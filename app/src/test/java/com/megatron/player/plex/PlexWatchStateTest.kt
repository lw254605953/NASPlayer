package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexWatchStateTest {
    @Test fun artworkHasNoProgressTrackUntilPlaybackHasStarted() {
        for (type in listOf("movie", "episode")) {
            val item = PlexMovie("item", "视频", type = type, durationMs = 1000)
            assertNull(plexThumbnailProgress(item, episodeProgress = true))
            assertNull(plexThumbnailProgress(item.copy(viewOffsetMs = -10), episodeProgress = true))
            assertEquals(.25f, plexThumbnailProgress(item.copy(viewOffsetMs = 250), episodeProgress = true)!!, 0f)
        }
        assertNull(plexThumbnailProgress(PlexMovie("show", "电视剧", type = "show", leafCount = 10)))
    }

    @Test fun completedEpisodeKeepsProgressAndLibraryDefaultsToFourColumns() {
        val watched = PlexMovie("e", "已看", type = "episode", watched = true)
        assertEquals(1f, plexThumbnailProgress(watched, episodeProgress = true)!!, 0f)
        assertNull(plexThumbnailProgress(watched))
        assertEquals(PlexViewMode.POSTER_FOUR, PlexLibraryOptions().view)
    }
    private fun parse(type: String, fields: String) = PlexMetadataParser.parseMovie(
        JSONObject("""{"ratingKey":"1","type":"$type",$fields}"""),
    )!!

    @Test fun aContainerViewCountDoesNotMeanEveryEpisodeWasWatched() {
        for (type in listOf("show", "season")) {
            val partial = parse(type, """"viewCount":1,"leafCount":10,"viewedLeafCount":1""")
            assertFalse(partial.watched)
            assertEquals(.1f, plexWatchProgress(partial)!!, .0001f)
            assertTrue(parse(type, """"viewCount":0,"leafCount":10,"viewedLeafCount":10""").watched)
            assertFalse(parse(type, """"viewCount":9,"viewedLeafCount":10""").watched)
            assertFalse(parse(type, """"viewCount":9,"leafCount":0,"viewedLeafCount":0""").watched)
        }
    }

    @Test fun movieAndEpisodeCompletionKeepTheirOwnViewCount() {
        for (type in listOf("movie", "episode")) {
            assertTrue(parse(type, """"viewCount":1""").watched)
            assertFalse(parse(type, """"viewCount":0,"viewOffset":25000,"duration":100000""").watched)
        }
    }

    @Test fun progressHandlesUnwatchedResumeCompletedAndMissingDuration() {
        val episode = PlexMovie("e", "集", type = "episode", durationMs = 1000)
        assertEquals(0f, plexWatchProgress(episode)!!, 0f)
        assertEquals(.25f, plexWatchProgress(episode.copy(viewOffsetMs = 250))!!, 0f)
        assertEquals(1f, plexWatchProgress(episode.copy(watched = true, viewOffsetMs = 0))!!, 0f)
        assertEquals(1f, plexWatchProgress(episode.copy(viewOffsetMs = 2000))!!, 0f)
        assertEquals(0f, plexWatchProgress(episode.copy(viewOffsetMs = -10))!!, 0f)
        assertNull(plexWatchProgress(episode.copy(durationMs = 0)))
    }

    @Test fun chineseEpisodeLabelsMatchPlaybackAndSpecials() {
        val episode = PlexMovie("e", "故事", type = "episode", parentIndex = 1, index = 2, grandparentTitle = "测试剧")
        assertEquals("第1季 · 第2集", plexEpisodeLabel(episode))
        assertEquals("第2集", plexEpisodeLabel(episode, includeSeason = false))
        assertEquals("测试剧 · 第1季 · 第2集 · 故事", plexPlaybackTitle(episode))
        assertEquals("特别篇 · 第2集", plexEpisodeLabel(episode.copy(parentIndex = 0)))
        assertEquals("故事", plexEpisodeLabel(episode.copy(index = -1)))
    }
}
