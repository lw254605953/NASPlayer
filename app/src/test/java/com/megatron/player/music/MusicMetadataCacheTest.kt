package com.megatron.player.music

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class MusicMetadataCacheTest {
    private val track = MusicTrack("id", "source", "Music/song.flac", "Song", artist = "Artist", indexedAt = 10, metadataVersion = MUSIC_METADATA_VERSION)
    @Test fun legacyIndexIsRepairedAndSuccessfulNoCoverIsReusable() {
        assertTrue(musicNeedsMetadata(track.copy(metadataVersion = 0)))
        assertFalse(musicNeedsMetadata(track))
        assertTrue(musicNeedsMetadata(track.copy(indexedAt = 0)))
    }
    @Test fun missingCoverIsRepairedWhilePresentCoverIsReused() {
        val file = File.createTempFile("music-cover", ".jpg")
        try {
            val withArt = track.copy(artworkPath = file.absolutePath)
            assertFalse(musicNeedsMetadata(withArt))
            assertTrue(file.delete())
            assertTrue(musicNeedsMetadata(withArt))
        } finally { file.delete() }
    }
    @Test fun metadataVersionAndLabelsSurviveDatabaseRecordRoundTrip() {
        assertEquals(track, musicTrack(track.json()))
        val legacy = track.json().apply { remove("metadataVersion") }
        val restored = musicTrack(JSONObject(legacy.toString()))
        assertEquals("Artist", restored.artist)
        assertTrue(musicNeedsMetadata(restored))
    }
}
