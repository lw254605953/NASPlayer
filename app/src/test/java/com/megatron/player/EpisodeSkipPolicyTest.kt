package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class EpisodeSkipPolicyTest {
    private fun policy(episode: Boolean = true, exact: Boolean = false) = EpisodeSkipPolicy().apply { configure(episode, exact) }
    private fun EpisodeSkipPolicy.at(position: Long, playing: Boolean = true, duration: Long = 600_000,
        seekable: Boolean = true, intro: Int = 30, outro: Int = 30) = next(position, duration, seekable, playing, intro, outro)

    @Test fun startsAtIntroBoundaryButNeverRewindsResume() {
        assertEquals(EpisodeSkipAction(30_000), policy().at(0))
        assertNull(policy().at(90_000))
    }
    @Test fun disabledMoviesExactPointsUnknownTimelinesAndShortEpisodesStayUntouched() {
        assertNull(policy().at(0, intro = 0, outro = 0))
        assertNull(policy(episode = false).at(0))
        assertNull(policy(exact = true).at(0))
        assertNull(policy(exact = true).at(590_000))
        assertNull(policy().at(0, duration = -1))
        assertNull(policy().at(0, seekable = false))
        assertNull(policy().at(0, duration = 60_000))
    }
    @Test fun introIsOnceAndManualReviewOfTailIsAllowed() {
        val p = policy()
        assertNotNull(p.at(0))
        p.manualSeek(0, 600_000, 30, 30)
        assertNull(p.at(0))
        p.manualSeek(580_000, 600_000, 30, 30)
        assertNull(p.at(580_000))
        p.manualSeek(100_000, 600_000, 30, 30)
        assertEquals(EpisodeSkipAction(600_000, finish = true), p.at(570_000))
    }
    @Test fun tailEndsOnceOnlyWhilePlayingAndDoesNotPretendUnknownDurationIsKnown() {
        val p = policy()
        assertNull(p.at(569_999))
        assertNull(p.at(570_000, playing = false))
        assertEquals(EpisodeSkipAction(600_000, finish = true), p.at(570_000))
        assertNull(p.at(580_000))
    }
    @Test fun lateEpisodeMetadataCanEnableSkippingWithoutRepreparingPlayer() {
        val p = policy(episode = false)
        assertNull(p.at(1_000))
        p.configure(true, false)
        assertEquals(EpisodeSkipAction(30_000), p.at(1_000))
    }
}
