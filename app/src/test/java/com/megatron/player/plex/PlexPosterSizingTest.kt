package com.megatron.player.plex

import org.junit.Assert.*
import org.junit.Test

class PlexPosterSizingTest {
    @Test fun coversActualPosterDimensionsWithStableBuckets() {
        assertEquals(128, PlexPosterSizing.edge(64, 96))
        assertEquals(384, PlexPosterSizing.edge(220, 330))
        assertEquals(768, PlexPosterSizing.edge(460, 690))
        assertEquals(768, PlexPosterSizing.edge(Int.MAX_VALUE, 720))
        assertEquals(768, PlexPosterSizing.edge(0, 0))
        assertEquals(1536, PlexPosterSizing.edge(4000, 6000))
    }
    @Test fun sampledImagePreservesDetailUntilFinalResize() {
        assertEquals(2, PlexPosterSizing.sample(720, 1080, 384))
        assertEquals(1, PlexPosterSizing.sample(720, 1080, 768))
        assertEquals(8, PlexPosterSizing.sample(4000, 6000, 512))
        assertEquals(1, PlexPosterSizing.sample(32, 48, 128))
        assertTrue(runCatching { PlexPosterSizing.sample(0, 100, 128) }.isFailure)
    }
}
