package com.megatron.player.music

import org.junit.Assert.*
import org.junit.Test

class MusicQueueWindowTest {
    private fun tracks(count: Int) = (1..count).map { MusicTrack("$it", "nas", "Music/$it.flac", "歌曲$it") }
    private fun assertWindow(count: Int, selected: Int, first: Int, last: Int) {
        val window = musicQueueWindow(tracks(count), "$selected")!!
        assertEquals(first.toString(), window.tracks.first().id); assertEquals(last.toString(), window.tracks.last().id)
        assertEquals(minOf(count, 500), window.tracks.size); assertEquals(selected.toString(), window.tracks[window.startIndex].id)
    }
    @Test fun beginningAndEarlyTracksKeepTheFirst500() { assertWindow(900,1,1,500);assertWindow(900,100,1,500) }
    @Test fun middleSelectionIsCenteredWith500Tracks() { assertWindow(900,350,100,599) }
    @Test fun nearEndPadsFromBeforeAndStillIncludesTheLastTrack() { assertWindow(550,350,51,550);assertWindow(900,900,401,900) }
    @Test fun smallDirectoriesAndDuplicateIdsRemainPlayable() {
        assertWindow(12,12,1,12)
        val window = musicQueueWindow(tracks(700)+tracks(700),"650")!!
        assertEquals(500,window.tracks.size);assertEquals(500,window.tracks.map { it.id }.toSet().size)
        assertEquals("650",window.tracks[window.startIndex].id)
    }
    @Test fun largeTagPayloadShrinksTheWindowInsteadOfDroppingTheRequestedTrack() {
        val window = musicQueueWindow(tracks(900).map { it.copy(artist="歌".repeat(2048)) }, "850",maxBytes=20_000)!!
        assertTrue(window.tracks.size<500);assertEquals("850",window.tracks[window.startIndex].id)
        assertTrue(window.tracks.sumOf { it.json().toString().toByteArray().size.toLong() }<=20_000)
    }
    @Test fun missingSelectionDoesNotStartAnotherSong() { assertNull(musicQueueWindow(tracks(5),"missing")) }
}
