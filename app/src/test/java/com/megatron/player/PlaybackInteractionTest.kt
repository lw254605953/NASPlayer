package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackInteractionTest {
    @Test fun parsesSecondsMinutesAndHours() {
        assertEquals(90_000L, parsePlaybackTimeMs(" 90 "))
        assertEquals(83_000L, parsePlaybackTimeMs("01:23"))
        assertEquals(5_400_000L, parsePlaybackTimeMs("90:00"))
        assertEquals(5_023_000L, parsePlaybackTimeMs("1:23:43"))
        assertEquals(0L, parsePlaybackTimeMs("00:00:00"))
    }
    @Test fun rejectsInvalidAndOverflowingTime() {
        listOf("", "-1", "1.5", "1:60", "1:60:00", "1::2", "1:2:3:4", "1:2x", "+3", "9223372036854775807", "999999999999999:00:00").forEach {
            assertNull(it, parsePlaybackTimeMs(it))
        }
    }
    @Test fun formatsHourBoundary() {
        assertEquals("00:00", formatPlaybackTime(0))
        assertEquals("01:23", formatPlaybackTime(83_987))
        assertEquals("1:00:01", formatPlaybackTime(3_601_000))
    }
    @Test fun limitsLongMovieSwipeToTwoMinutesPerWidth() {
        assertEquals(1_060_000L, gestureSeekPositionMs(1_000_000, 7_200_000, 150f, 300))
        assertEquals(880_000L, gestureSeekPositionMs(1_000_000, 7_200_000, -300f, 300))
    }
    @Test fun clampsShortClipAndBothEnds() {
        assertEquals(20_000L, gestureSeekPositionMs(10_000, 30_000, 100f, 300))
        assertEquals(30_000L, gestureSeekPositionMs(10_000, 30_000, 900f, 300))
        assertEquals(0L, gestureSeekPositionMs(10_000, 30_000, -900f, 300))
    }
    @Test fun refusesUnknownTimelineAndInvalidMotion() {
        assertNull(gestureSeekPositionMs(0, -1, 100f, 300))
        assertNull(gestureSeekPositionMs(0, 0, 100f, 300))
        assertNull(gestureSeekPositionMs(0, 30_000, 100f, 0))
        assertNull(gestureSeekPositionMs(0, 30_000, Float.NaN, 300))
        assertNull(gestureSeekPositionMs(0, 30_000, Float.POSITIVE_INFINITY, 300))
    }
}
