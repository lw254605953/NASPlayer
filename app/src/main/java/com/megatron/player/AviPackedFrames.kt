package com.megatron.player

/** MPEG-4 Part 2 packed B-frame packet normalization; no decoding or re-encoding. */
internal class AviPackedFrames {
    private var delayed: ByteArray? = null

    // Matches the packed-frame/N-VOP convention handled by FFmpeg's mpeg4_unpack_bframes BSF.
    // The compressed picture bytes stay intact; only packet boundaries and the packed marker change.
    fun normalize(packet: ByteArray): ByteArray {
        var data = packet
        val vops = ArrayList<Int>(2)
        var i = 0
        while (i + 3 < packet.size) {
            if (!startCode(packet, i)) { i++; continue }
            val type = packet[i + 3].toInt() and 255
            if (type == 0xb6) vops += i
            if (type == 0xb2 && i + 8 <= packet.size &&
                packet[i + 4] == 'D'.code.toByte() && packet[i + 5] == 'i'.code.toByte() &&
                packet[i + 6] == 'v'.code.toByte() && packet[i + 7] == 'X'.code.toByte()) {
                var end = i + 8
                while (end < packet.size && end < i + 4 + 255 && !startCode(packet, end)) end++
                for (j in i + 8 until end) {
                    if (packet[j] == 'p'.code.toByte() && j + 1 < packet.size && packet[j + 1] == 0.toByte()) {
                        if (data === packet) data = packet.copyOf()
                        data[j] = 0
                        break
                    }
                }
            }
            i += 4
        }
        if (vops.size >= 2) {
            val split = vops[1]
            delayed = data.copyOfRange(split, data.size)
            return data.copyOfRange(0, split)
        }
        val previous = delayed
        if (vops.size == 1 && previous != null) {
            // A tiny not-coded VOP is the slot reserved by packed AVI for the saved B-frame.
            delayed = data.takeIf { it.size > 19 }
            return previous
        }
        return data
    }

    fun reset() { delayed = null }

    private fun startCode(data: ByteArray, i: Int) = i + 3 < data.size &&
        data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
}
