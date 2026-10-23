package com.megatron.player

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class VideoQualityInfoTest {
    @Test fun sourceHdrClassificationDoesNotPretendUnknownSignalsAreSdr() {
        assertEquals("未报告", videoHdrLabel(null, null, null))
        assertEquals("未报告", videoHdrLabel("video/hevc", "hvc1", androidx.media3.common.Format.NO_VALUE))
        assertEquals("SDR", videoHdrLabel("video/avc", null, C.COLOR_TRANSFER_SDR))
        assertTrue(videoHdrLabel("video/hevc", null, C.COLOR_TRANSFER_ST2084).startsWith("HDR（PQ"))
        assertEquals("HDR（HLG）", videoHdrLabel("video/hevc", null, C.COLOR_TRANSFER_HLG))
        assertEquals("杜比视界（片源标识）", videoHdrLabel("video/dolby-vision", null, null))
        assertEquals("杜比视界（片源标识）", videoHdrLabel("video/hevc", "dvh1.05.06", null))
        assertEquals("未报告", videoDimensions(0, 2160))
        assertEquals("3840×2160", videoDimensions(3840, 2160))
    }
    @Test fun readWindowExpiresOldSamplesAndBoundsItsObservationInterval() {
        var time = 10000L
        val window = ReadWindow { time }
        window.add(1000); assertEquals(1000, window.count()); assertEquals(1000.0, window.perSecond(), .001)
        time += 2500; window.add(1000); assertEquals(2000, window.count()); assertEquals(800.0, window.perSecond(), .001)
        time += 2500; assertEquals(1000, window.count())
        time += 2500; assertEquals(0, window.count())
    }
}
