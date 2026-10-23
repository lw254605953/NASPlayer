package com.megatron.player.plex

import org.junit.Assert.*
import org.junit.Test

class PlexPaginationTest {
    private fun page(offset: Int, count: Int, total: Int) = PlexMoviePage(List(count) { PlexMovie("${offset + it}", "影片") }, total, offset)

    @Test fun advancesAcrossSixtyItemBatchesUntilTheLastPage() {
        assertEquals(60, nextPlexPageOffset(page(0, 60, 145), 0))
        assertEquals(120, nextPlexPageOffset(page(60, 60, 145), 60))
        assertNull(nextPlexPageOffset(page(120, 25, 145), 120))
    }

    @Test fun emptyPageStopsEvenIfServerTotalIsStale() {
        assertNull(nextPlexPageOffset(page(60, 0, 145), 60))
        assertNull(nextPlexPageOffset(page(0, 0, 0), 0))
    }

    @Test fun serverCursorIsIndependentOfDeduplicatedCardsAndShortPages() {
        val overlap = PlexMoviePage(List(5) { PlexMovie("same", "影片") }, 145, 60)
        assertEquals(65, nextPlexPageOffset(overlap, 60))
        assertNull(nextPlexPageOffset(page(Int.MAX_VALUE - 2, 5, Int.MAX_VALUE), Int.MAX_VALUE - 2))
    }

    @Test fun serverIgnoringRequestedOffsetCannotLoopTheFirstPage() {
        assertTrue(runCatching { nextPlexPageOffset(page(0, 60, 145), 60) }.isFailure)
    }
}
