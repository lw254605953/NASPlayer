package com.megatron.player.music

import org.junit.Assert.*
import org.junit.Test

class MusicVideoRootsTest {
    @Test fun categoriesKeepSeparateDirectoriesAndRoundTripUnicodePaths() {
        val roots = listOf(MusicRoot("nas", "/音乐/MV/", "MV"), MusicRoot("nas", "音乐/MV", "重复"), MusicRoot("other", "音乐/MV", "其他"))
        val decoded = decodeMusicVideoRoots(encodeMusicVideoRoots(roots))
        assertEquals(2, decoded.size)
        assertEquals("音乐/MV", decoded.first().path)
        assertEquals("MV", decoded.first().name)
        assertNotEquals(MusicVideoCategory.MV.preferenceKey, MusicVideoCategory.CONCERT.preferenceKey)
        assertTrue(decodeMusicVideoRoots(null).isEmpty())
    }
}
