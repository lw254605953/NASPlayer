package com.megatron.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MusicFilePathsTest {
    @Test fun m4rUsesMp4AudioAndWorksWithFileOperations() {
        assertTrue(isMusicFile("铃声.M4R"))
        assertEquals("audio/mp4", musicContainerMimeType("铃声.M4R"))
        assertEquals(musicContainerMimeType("歌曲.m4a"), musicContainerMimeType("铃声.m4r"))
        assertEquals("Target/铃声.M4R", musicMovePath("Songs/铃声.M4R", "Target"))
        assertFalse(isMusicFile("铃声.m4r.backup"))
    }
    @Test fun rootDestinationPreservesLiteralFilename() {
        assertEquals("中文 %20 #歌.flac", musicMovePath("Songs/中文 %20 #歌.flac", ""))
    }
    @Test fun traversalCannotLeaveConfiguredRoot() {
        assertThrows(IllegalArgumentException::class.java) { musicMovePath("Songs/a.flac", "../outside") }
        assertThrows(IllegalArgumentException::class.java) { musicMovePath("Songs/../a.flac", "Target") }
    }
    @Test fun sameDirectoryIsNotAMove() {
        assertThrows(IllegalArgumentException::class.java) { musicMovePath("Songs/a.flac", "/Songs/") }
    }
    @Test fun nonMusicResourceCannotBeDeletedAsASong() {
        assertThrows(IllegalArgumentException::class.java) { musicMovePath("Songs/notes.txt", "Target") }
    }
}
