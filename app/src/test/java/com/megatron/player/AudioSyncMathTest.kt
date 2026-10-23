package com.megatron.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSyncMathTest {
    @Test
    fun positiveOffsetDelaysAndNegativeOffsetAdvances() {
        assertEquals(1_250_000L, AudioSyncMath.shiftPresentationTimeUs(1_000_000L, 250))
        assertEquals(750_000L, AudioSyncMath.shiftPresentationTimeUs(1_000_000L, -250))
    }

    @Test
    fun offsetIsClampedToDesignRange() {
        assertEquals(-2_000, AudioSyncMath.clamp(-9_000))
        assertEquals(2_000, AudioSyncMath.clamp(9_000))
        assertEquals(50, AudioSyncMath.clamp(50))
    }
}
