package com.megatron.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStartPositionTest {
    @Test fun bookmarkNearEndDoesNotResetLikeResumeHistory() {
        val record = RecentVideo("mpplex://server/123?part=%2Flibrary%2Fparts%2F99%2Ffile.mkv", "片尾", 98_765L, 100_000L)
        assertEquals(0L, videoStartPosition(record, exactPosition = false))
        assertEquals(98_765L, videoStartPosition(record, exactPosition = true))
    }
    @Test fun zeroAndUnknownDurationRemainValidExplicitTargets() {
        assertEquals(0L, videoStartPosition(RecentVideo("mpdav://nas/movie.mkv", "开场", 0L, 100_000L), true))
        assertEquals(12_345L, videoStartPosition(RecentVideo("content://videos/1", "本地", 12_345L), true))
    }
}
