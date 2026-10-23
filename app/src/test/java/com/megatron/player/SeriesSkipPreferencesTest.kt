package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class SeriesSkipPreferencesTest {
    @Test fun absentOverrideUsesGlobalWhileExplicitZeroClosesSkipping() {
        val global = EpisodeSkipTimes(30, 60)
        assertEquals(global, resolveEpisodeSkipTimes(global, null))
        assertEquals(EpisodeSkipTimes(0, 0), resolveEpisodeSkipTimes(global, EpisodeSkipTimes(0, 0)))
        assertEquals(EpisodeSkipTimes(90, 45), resolveEpisodeSkipTimes(global, EpisodeSkipTimes(90, 45)))
    }
    @Test fun seriesAndServerIdentityAreIsolatedAndStable() {
        assertEquals(SeriesSkipKey("server", "200").storageKey, SeriesSkipKey("server", "200").storageKey)
        assertNotEquals(SeriesSkipKey("server", "200").storageKey, SeriesSkipKey("server2", "200").storageKey)
        assertNotEquals(SeriesSkipKey("server", "200").storageKey, SeriesSkipKey("server", "201").storageKey)
        assertNotEquals(SeriesSkipKey("a", "b\u0000c").storageKey, SeriesSkipKey("a\u0000b", "c").storageKey)
    }
}
