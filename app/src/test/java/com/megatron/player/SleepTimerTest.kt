package com.megatron.player
import org.junit.Assert.*
import org.junit.Test
class SleepTimerTest {
    @Test fun customHoursAndMinutesHaveNoAmbiguousUnit() {
        assertEquals(30*60_000L,sleepDurationMs(0,30));assertEquals(90*60_000L,sleepDurationMs(1,30))
        assertNull(sleepDurationMs(0,0));assertNull(sleepDurationMs(1,60));assertNull(sleepDurationMs(-1,1));assertNull(sleepDurationMs(24,0))
    }
    @Test fun countdownClampsAtZeroAndCanceledTimersStayInactive() {
        assertEquals(1500,sleepRemainingMs(3000,1500));assertEquals(0,sleepRemainingMs(3000,4000));assertEquals(0,sleepRemainingMs(0,100))
    }
    @Test fun remainingTimeRoundsUpAndDisplaysHoursWhenNeeded() {
        assertEquals("00:01",sleepTimerText(1));assertEquals("30:00",sleepTimerText(30*60_000));assertEquals("2:00:00",sleepTimerText(120*60_000))
    }
}
