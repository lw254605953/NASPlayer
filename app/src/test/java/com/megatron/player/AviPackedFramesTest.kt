package com.megatron.player

import org.junit.Assert.*
import org.junit.Test

class AviPackedFramesTest {
    private fun vop(vararg bytes: Int) = byteArrayOf(0, 0, 1, 0xb6.toByte()) + bytes.map(Int::toByte).toByteArray()

    @Test fun splitsPackedFramesAndReplacesDummyPacketWithoutChangingCodedBytes() {
        val filter = AviPackedFrames()
        val first = vop(*IntArray(30) { 0x51 })
        val second = vop(*IntArray(26) { 0x91 })
        assertArrayEquals(first, filter.normalize(first + second))
        assertArrayEquals(second, filter.normalize(vop(0x51, 0x99, 0xe9)))
        val third = vop(*IntArray(24) { 0x20 })
        assertArrayEquals(third, filter.normalize(third))
    }

    @Test fun removesOnlyPackedMarkerInsideDivxUserData() {
        val filter = AviPackedFrames()
        val original = byteArrayOf(0, 0, 1, 0xb2.toByte()) + "DivX503b1393p".toByteArray() + byteArrayOf(0) + vop(0x10, 0x70, 0)
        val expected = original.copyOf().also { it[16] = 0 }
        assertArrayEquals(expected, filter.normalize(original))
        assertEquals('p'.code.toByte(), original[16])
    }

    @Test fun seekDiscardsPendingFrameFromOldPosition() {
        val filter = AviPackedFrames()
        filter.normalize(vop(*IntArray(25) { 0x51 }) + vop(*IntArray(26) { 0x91 }))
        filter.reset()
        val next = vop(*IntArray(24) { 0x11 })
        assertArrayEquals(next, filter.normalize(next))
    }

    @Test fun nonPackedFramesAndUnrelatedUserDataStayUnchanged() {
        val filter = AviPackedFrames()
        val packet = byteArrayOf(0, 0, 1, 0xb2.toByte()) + "OtherApp".toByteArray() + byteArrayOf(0) + vop(0x10, 0x70, 0)
        assertArrayEquals(packet, filter.normalize(packet))
        assertArrayEquals(byteArrayOf(0, 0), filter.normalize(byteArrayOf(0, 0)))
    }
}
