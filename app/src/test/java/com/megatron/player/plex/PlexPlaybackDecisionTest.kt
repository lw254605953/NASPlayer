package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexPlaybackDecisionTest {
    private val selection = PlexMediaSelection(0, 0, 120000)
    private fun parse(body: String) = PlexPlaybackDecision.fromJson(JSONObject(body), "123", selection)

    @Test fun explicitDirectPlayPermissionIsAccepted() {
        val decision = parse("""{"MediaContainer":{"generalDecisionCode":1000,"directPlayDecisionCode":1000,"generalDecisionText":"Direct play OK."}}""")
        assertTrue(decision.allowsDirectPlay)
        decision.requireDirectPlay()
    }

    @Test fun finalMdeDecisionControlsClientCapabilityWithoutOverridingGeneralDenial() {
        assertTrue(parse("""{"MediaContainer":{"generalDecisionCode":1001,"directPlayDecisionCode":3000,"mdeDecisionCode":1000}}""").allowsDirectPlay)
        assertFalse(parse("""{"MediaContainer":{"generalDecisionCode":1000,"directPlayDecisionCode":1000,"mdeDecisionCode":1001}}""").allowsDirectPlay)
        assertFalse(parse("""{"MediaContainer":{"generalDecisionCode":2000,"directPlayDecisionCode":1000,"mdeDecisionCode":1000}}""").allowsDirectPlay)
    }

    @Test fun denialOverridesAnOtherwiseDirectPlayableFile() {
        val decision = parse("""{"MediaContainer":{"generalDecisionCode":2000,"mdeDecisionCode":2000,"directPlayDecisionCode":1000,"generalDecisionText":"A Plex Pass is required to remotely stream to this app."}}""")
        assertFalse(decision.allowsDirectPlay)
        val error = runCatching { decision.requireDirectPlay() }.exceptionOrNull() as PlexPlaybackFailure
        assertTrue(error.description().contains("Plex 拒绝此次播放"))
        assertTrue(error.description().contains("Plex Pass is required"))
        assertSame(decision, error.decision)
    }

    @Test fun conversionPermissionDoesNotMeanDirectPlayPermission() {
        val decision = parse("""{"MediaContainer":{"generalDecisionCode":1001,"directPlayDecisionCode":3000,"generalDecisionText":"Direct play not available; Conversion OK."}}""")
        assertFalse(decision.allowsDirectPlay)
        assertTrue((runCatching { decision.requireDirectPlay() }.exceptionOrNull() as PlexPlaybackFailure).userMessage.contains("转换后播放"))
    }

    @Test fun missingDecisionCodesAndWrongItemCannotOpenTheFile() {
        assertFalse(parse("""{"MediaContainer":{"Metadata":[{"ratingKey":"123","Media":[{"Part":[{"decision":"directplay"}]}]}]}}""").allowsDirectPlay)
        val failure = runCatching { parse("""{"MediaContainer":{"generalDecisionCode":1000,"Metadata":[{"ratingKey":"999"}]}}""") }.exceptionOrNull()
        assertTrue(failure is PlexPlaybackFailure)
    }

    @Test fun terminationAlwaysPreventsOpeningTheFile() {
        assertFalse(parse("""{"MediaContainer":{"generalDecisionCode":1000,"terminationCode":2001,"terminationText":"Playback was stopped by server."}}""").allowsDirectPlay)
    }

    @Test fun exactMediaAndPartAreUsedWithoutFakingNetworkLocation() {
        val client = PlexClient("client", "playback-session")
        val metadata = JSONObject("""{"MediaContainer":{"Metadata":[{"ratingKey":"123","duration":120000,"Media":[{"Part":[{"key":"/library/parts/1/1/file.mkv"}]},{"Part":[{"key":"/library/parts/2/1/file.mkv"},{"key":"/library/parts/3/1/file.mkv"}]}]}]}}""")
        val selected = client.selectPlaybackPart(metadata, "123", "/library/parts/3/1/file.mkv")
        assertEquals(PlexMediaSelection(1, 1, 120000), selected)
        val credentials = PlexCredentials(PlexServerConfig("s", "m", "Plex", "https://nas.example/plex/"), "secret")
        val url = client.decisionUrl(credentials, "123", selected)
        assertEquals("/plex/video/:/transcode/universal/decision", url.encodedPath)
        assertEquals("1", url.queryParameter("mediaIndex"))
        assertEquals("1", url.queryParameter("partIndex"))
        assertEquals("/library/metadata/123", url.queryParameter("path"))
        assertEquals("1", url.queryParameter("directPlay"))
        assertEquals("0", url.queryParameter("directStream"))
        assertNull(url.queryParameter("location"))
        assertNull(url.queryParameter("X-Plex-Token"))
        assertFalse(url.toString().contains("secret"))
        assertTrue(runCatching { client.selectPlaybackPart(metadata, "123", "/library/parts/999/1/file.mkv") }.exceptionOrNull() is PlexPlaybackFailure)
    }

    @Test fun serverExplanationKeepsReasonButRemovesPrivateValues() {
        val text = safePlexDecisionText("Playback denied. https://u:pw@private.example/file?X-Plex-Token=URLSECRET X-Plex-Token => HEADERSECRET token=QUERYSECRET \"password\":\"PASSSECRET\" Authorization: Bearer BEARERSECRET /volume1/Movies/private.mkv")
        assertTrue(text.contains("Playback denied"))
        listOf("private.example", "URLSECRET", "HEADERSECRET", "QUERYSECRET", "PASSSECRET", "BEARERSECRET", "/volume1/", "private.mkv").forEach {
            assertFalse("Private field leaked: $it", text.contains(it))
        }
    }
}
