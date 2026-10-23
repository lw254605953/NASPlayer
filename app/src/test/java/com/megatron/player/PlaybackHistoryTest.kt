package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    @Test fun updatingOneVideoKeepsOtherVideosAndLatestPosition() {
        val result = updatedHistory(
            listOf(RecentVideo("content://one", "one", 10L), RecentVideo("content://two", "two", 20L)),
            RecentVideo("content://one", "one", 500L),
        )
        assertEquals(listOf("content://one", "content://two"), result.map { it.uri })
        assertEquals(500L, result.first().positionMs)
        assertEquals(20L, result.last().positionMs)
    }
    @Test fun onlyKeepsTheMostRecentTenVideos() {
        val old = (1..10).map { RecentVideo("content://$it", "$it") }
        val result = updatedHistory(old, RecentVideo("content://new", "new"), limit = 10)
        assertEquals(10, result.size)
        assertEquals("content://new", result.first().uri)
        assertFalse(result.any { it.uri == "content://10" })
    }
    @Test fun completeHistoryKeepsFiveHundredAndDeduplicatesVersions() {
        val old = (1..500).map { RecentVideo("content://$it", "$it") }
        assertEquals(500, updatedHistory(old, RecentVideo("content://new", "new")).size)
        val versioned = updatedHistory(listOf(RecentVideo("mpplex://server/9?part=old", "片名")), RecentVideo("mpplex://server/9?part=new", "片名"))
        assertEquals(1, versioned.size)
        assertTrue(versioned.single().uri.endsWith("new"))
        assertEquals(2, updatedHistory(versioned, RecentVideo("mpplex://other/9?part=one", "片名")).size)
    }
    @Test fun differentFilesWithTheSameTitleKeepSeparatePositions() {
        val result = updatedHistory(listOf(RecentVideo("content://one", "movie", 500L)), RecentVideo("content://two", "movie", 900L))
        assertEquals(2, result.size)
        assertEquals(900L, result[0].positionMs)
        assertEquals(500L, result[1].positionMs)
    }
}
