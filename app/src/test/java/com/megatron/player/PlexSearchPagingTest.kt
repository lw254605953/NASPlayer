package com.megatron.player

import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.PlexMoviePage
import org.junit.Assert.*
import org.junit.Test

class PlexSearchPagingTest {
    private fun movie(key: Int) = PlexMovie(key.toString(), "影片$key")

    @Test fun returningToSearchRecognizesSnapshotMapKeysByTheirContents() {
        val state = PlexGlobalSearchState()
        state.searchedQuery = "测试"
        state.revision = 3
        state.groups["1"] = PlexSearchGroup(listOf(movie(1)), 1, 1)
        state.groups["2"] = PlexSearchGroup(listOf(movie(2)), 1, 1)
        assertTrue(state.matches("测试", 3, listOf("2", "1")))
        assertEquals(1, state.groups["1"]!!.movies.size)
        assertFalse(state.matches("其他", 3, listOf("1", "2")))
        assertFalse(state.matches("测试", 4, listOf("1", "2")))
        assertFalse(state.matches("测试", 3, listOf("1")))
    }

    @Test fun appendsOnlyRequestedPageAndKeepsOtherGroupsIndependent() {
        val first = mergePlexSearchPage(PlexSearchGroup(), PlexMoviePage((1..20).map(::movie), 23, 0), 0)
        val otherLibrary = PlexSearchGroup(listOf(movie(99)), 1, 1)
        val last = mergePlexSearchPage(first, PlexMoviePage((21..23).map(::movie), 23, 20), 20)
        assertEquals(23, last.movies.size)
        assertEquals(23, last.nextOffset)
        assertEquals("1", last.movies.first().ratingKey)
        assertEquals("23", last.movies.last().ratingKey)
        assertEquals("99", otherLibrary.movies.single().ratingKey)
        assertFalse(last.loading)
    }

    @Test fun freshQueryReplacesPreviousResults() {
        val previous = PlexSearchGroup(listOf(movie(1)), 100, 1)
        val updated = mergePlexSearchPage(previous, PlexMoviePage(listOf(movie(8)), 1, 0), 0)
        assertEquals(listOf("8"), updated.movies.map { it.ratingKey })
    }

    @Test fun rejectsIgnoredOffsetRepeatedPageAndEarlyEnd() {
        val first = PlexSearchGroup((1..20).map(::movie), 100, 20)
        assertTrue(runCatching { mergePlexSearchPage(first, PlexMoviePage(listOf(movie(21)), 100, 0), 20) }.isFailure)
        assertTrue(runCatching { mergePlexSearchPage(first, PlexMoviePage((1..20).map(::movie), 100, 20), 20) }.isFailure)
        assertTrue(runCatching { mergePlexSearchPage(first, PlexMoviePage(emptyList(), 100, 20), 20) }.isFailure)
        assertTrue(runCatching { mergePlexSearchPage(first, PlexMoviePage(listOf(movie(21), movie(21)), 100, 20), 20) }.isFailure)
        assertTrue(runCatching { mergePlexSearchPage(first, PlexMoviePage(listOf(movie(21)), 100, 21), 21) }.isFailure)
    }

    @Test fun allowsEmptyLibraryAndConfirmedFinalEmptyPage() {
        assertEquals(0, mergePlexSearchPage(PlexSearchGroup(), PlexMoviePage(emptyList(), 0, 0), 0).nextOffset)
        val final = PlexSearchGroup(listOf(movie(1)), 2, 1)
        assertEquals(1, mergePlexSearchPage(final, PlexMoviePage(emptyList(), 1, 1), 1).nextOffset)
    }
}
