package com.megatron.player.music.metadata

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicCatalogPolicyTest {
    @Test fun privateAndMeaninglessCluesDoNotLeaveDevice() {
        assertNull(musicClue("00001.flac"))
        assertNull(musicClue("Track 01.mp3"))
        assertNull(musicClue("0123456789abcdef0123456789abcdef.flac"))
        assertNull(musicClue("token=secret password.mp3"))
        assertEquals("歌手 - 歌名", musicClue("Songs/歌手 - 歌名.flac"))
    }
    @Test fun catalogueCannotFetchModelChosenOrInternalUrls() {
        listOf("https://127.0.0.1/a", "http://musicbrainz.org/a", "https://musicbrainz.org.attacker.com/a", "https://user:secret@archive.org/a", "https://lrclib.net:32400/a").forEach { assertFalse(MusicCatalog.allowedCatalogUrl(it.toHttpUrl())) }
        assertTrue(MusicCatalog.allowedCatalogUrl("https://coverartarchive.org/release/a/front-500".toHttpUrl()))
    }
    @Test fun corruptCandidateCannotBecomeAnArbitraryArtworkPath() {
        assertThrows(IllegalArgumentException::class.java) { MusicMatch.read(JSONObject().put("recording", "http://nas/private").put("title", "song")) }
        assertFalse(validMbid("../../credentials"))
        assertTrue(validMbid("00000000-0000-0000-0000-000000000001"))
    }
}
