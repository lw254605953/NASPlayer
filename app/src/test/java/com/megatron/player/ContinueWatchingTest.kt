package com.megatron.player

import com.megatron.player.plex.PlexMovie
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingTest {
    @Test fun oneCardPerShowPreservesServerEpisodeAndOrder() {
        val next = PlexMovie("e2", "下一集", type = "episode", grandparentRatingKey = "s", durationMs = 1000)
        val other = next.copy(ratingKey = "e3")
        val movie = PlexMovie("m", "电影", viewOffsetMs = 100, durationMs = 1000)
        assertEquals(listOf("e2", "m"), groupedPlexContinueWatching(listOf(next, other, movie)).map { it.ratingKey })
    }

    @Test fun completedMoviesAndEpisodesAreExcludedWithoutInventingNewTargets() {
        val movie = PlexMovie("m", "电影", viewOffsetMs = 250, durationMs = 1000)
        assertEquals(listOf(movie), groupedPlexContinueWatching(listOf(movie, movie.copy(ratingKey = "w", watched = true), movie.copy(ratingKey = "near", viewOffsetMs = 950), movie.copy(ratingKey = "new", viewOffsetMs = 0))))
    }

    @Test fun fileResumeKeepsUnfinishedAndUsesLatestFirst() {
        val first = RecentVideo("one", "一", 10, 100, updatedAt = 1)
        val latest = RecentVideo("two", "二", 20, 0, updatedAt = 2)
        assertEquals(listOf(latest, first), unfinishedHistory(listOf(first, latest, first.copy(uri = "done", positionMs = 100), first.copy(uri = "new", positionMs = 0))))
    }
}
