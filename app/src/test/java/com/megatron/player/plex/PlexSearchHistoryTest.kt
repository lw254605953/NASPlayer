package com.megatron.player.plex

import org.junit.Assert.*
import org.junit.Test

class PlexSearchHistoryTest {
    @Test fun onlySubmittedNonEmptyQueriesAreRememberedAndDuplicatesMoveToFront() {
        assertEquals(listOf("星际穿越", "DUNE"), updatedSearchHistory(listOf("DUNE", "星际穿越"), "  星际穿越  "))
        assertEquals(listOf("dune", "星际穿越"), updatedSearchHistory(listOf("DUNE", "星际穿越"), "dune"))
        assertEquals(listOf("星际穿越"), updatedSearchHistory(listOf("星际穿越"), "  "))
    }

    @Test fun historyKeepsOnlyTheMostRecentTwelveTerms() {
        val history = (1..12).map { "影片$it" }
        val updated = updatedSearchHistory(history, "新的影片")
        assertEquals(12, updated.size)
        assertEquals("新的影片", updated.first())
        assertFalse(updated.contains("影片12"))
    }
}
