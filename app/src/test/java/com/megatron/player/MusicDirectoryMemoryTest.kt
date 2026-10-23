package com.megatron.player

import com.megatron.player.source.BrowserEntry
import org.junit.Assert.*
import org.junit.Test

class MusicDirectoryMemoryTest {
    @Test fun playerReturnReusesTheSameDirectoryWithoutRefreshOrReordering() {
        val memory = MusicDirectoryMemory()
        val entries = listOf(BrowserEntry("歌曲.flac", "Music/歌曲.flac", false))
        memory.put("nas/音乐", 2, 3, entries)
        repeat(3) { assertSame(entries, memory.get("nas/音乐", 2, 3)) }
        assertNull(memory.get("other-nas/音乐", 2, 3))
    }
    @Test fun manualRefreshOrFileAndSourceChangesRequireNewDirectoryData() {
        val memory = MusicDirectoryMemory()
        memory.put("folder", 2, 3, emptyList())
        assertNotNull(memory.get("folder", 2, 3))
        assertNull(memory.get("folder", 3, 3))
        assertNull(memory.get("folder", 2, 4))
        memory.invalidate("folder")
        assertNull(memory.get("folder", 2, 3))
    }
}
