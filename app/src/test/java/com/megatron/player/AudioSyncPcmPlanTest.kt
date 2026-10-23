package com.megatron.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSyncPcmPlanTest {
    @Test
    fun delayInsertsWholePcmFramesAndKeepsTimelineOffset() {
        val plan = AudioSyncPcmPlan.create(200, 48_000, 4)
        assertEquals(200_000L, plan.offsetUs)
        assertEquals(38_400, plan.delayBytes)
        assertEquals(0, plan.dropBytes)
    }

    @Test
    fun advanceDropsPrefixAndShiftsFirstRemainingSampleToZero() {
        val plan = AudioSyncPcmPlan.create(-2, 1_000, 2)
        val transform = AudioSyncPcmTransform(plan, 2, 1_000, androidx.media3.common.C.ENCODING_PCM_16BIT)
        val first = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(null, transform.mapInput(first, 0L))
        val second = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, transform.mapInput(second, 2_000L))
        // A partial sink write retries the same buffer; its timestamp must remain unchanged.
        second.position(second.position() + 2)
        assertEquals(0L, transform.mapInput(second, 2_000L))
    }

    @Test
    fun delayProducesZeroPcmAndRetainsItAcrossPartialWrites() {
        val plan = AudioSyncPcmPlan.create(2, 1_000, 2)
        val transform = AudioSyncPcmTransform(plan, 2, 1_000, androidx.media3.common.C.ENCODING_PCM_16BIT)
        val silence = transform.pendingSilence
        assertNotNull(silence)
        assertTrue(silence!!.remaining() == 4)
        while (silence.hasRemaining()) assertEquals(0.toByte(), silence.get())
        transform.markSilenceHandled()
        assertEquals(null, transform.pendingSilence)
    }
}
