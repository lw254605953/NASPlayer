package com.megatron.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.ts.MpegAudioReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.EOFException

/** AVI chunks need not coincide with MP3 frames; old Xvid files can also pack two VOPs per chunk. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class CompatibleAviExtractor(delegate: AviExtractor) : ForwardingExtractor(delegate) {
    private val streams = mutableMapOf<Int, FrameTrack>()

    override fun init(output: ExtractorOutput) {
        super.init(object : ForwardingExtractorOutput(output) {
            override fun track(id: Int, type: Int): TrackOutput = streams.getOrPut(id) {
                FrameTrack(output.track(id, type), id)
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        streams.values.forEach(FrameTrack::reset)
        super.seek(position, timeUs)
    }

    private class FrameTrack(private val target: TrackOutput, private val id: Int) : ForwardingTrackOutput(target) {
        private var mpegAudio: MpegAudioReader? = null
        private var mpegVideo = false
        private var audioClockStarted = false
        private var buffer = ByteArray(8192)
        private var used = 0
        private val packed = AviPackedFrames()
        private val active get() = mpegAudio != null || mpegVideo

        override fun format(format: Format) {
            super.format(format)
            mpegVideo = format.sampleMimeType == MimeTypes.VIDEO_MP4V
            if (format.sampleMimeType == MimeTypes.AUDIO_MPEG && mpegAudio == null) {
                mpegAudio = MpegAudioReader(format.language, format.roleFlags, MimeTypes.VIDEO_AVI).also { reader ->
                    reader.createTracks(object : ExtractorOutput {
                        override fun track(trackId: Int, type: Int) = target
                        override fun endTracks() = Unit
                        override fun seekMap(seekMap: SeekMap) = Unit
                    }, TsPayloadReader.TrackIdGenerator(id, 1))
                }
            }
        }

        fun reset() {
            used = 0
            audioClockStarted = false
            mpegAudio?.seek()
            packed.reset()
        }

        private fun capacity(count: Int) {
            if (count < 0 || count > MAX_PACKET - used) throw ParserException.createForMalformedContainer("AVI sample exceeds compatibility buffer limit", null)
            val required = used + count
            if (required > buffer.size) buffer = buffer.copyOf(maxOf(required, minOf(MAX_PACKET, buffer.size * 2)))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!active) return super.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val count = minOf(length, 65536)
            capacity(count)
            val read = input.read(buffer, used, count)
            if (read < 0) { if (!allowEndOfInput || used != 0) throw EOFException(); return C.RESULT_END_OF_INPUT }
            used += read
            return read
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(data: ParsableByteArray, length: Int) =
            sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!active) { super.sampleData(data, length, sampleDataPart); return }
            capacity(length)
            data.readBytes(buffer, used, length)
            used += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!active) { super.sampleMetadata(timeUs, flags, size, offset, cryptoData); return }
            // The pinned AviExtractor's ChunkReader always delivers one complete, unencrypted chunk.
            if (offset != 0 || size != used || cryptoData != null) throw ParserException.createForMalformedContainer("AVI sample boundaries are invalid", null)
            val reader = mpegAudio
            if (reader != null) {
                if (!audioClockStarted) { reader.packetStarted(timeUs, 0); audioClockStarted = true }
                reader.consume(ParsableByteArray(buffer, used))
            } else {
                val normalized = packed.normalize(buffer.copyOf(used))
                target.sampleData(ParsableByteArray(normalized), normalized.size)
                target.sampleMetadata(timeUs, flags, normalized.size, 0, null)
            }
            used = 0
        }
    }

    private companion object { const val MAX_PACKET = 16 * 1024 * 1024 }
}
