package com.megatron.player.backup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSchemaTest {
    private val names = setOf("player_preferences", "series_skip_v1")
    @Test fun legacyBackupRestoresEmptySeriesOverridesWithoutLosingGlobalValues() {
        val legacy = JSONObject().put("player_preferences", JSONObject().put("intro", 30))
        val migrated = migrateBackupPreferences(1, legacy, names)
        assertEquals(30, migrated.getJSONObject("player_preferences").getInt("intro"))
        assertEquals(0, migrated.getJSONObject("series_skip_v1").length())
        assertFalse(legacy.has("series_skip_v1"))
    }
    @Test fun newBackupPreservesOverridesAndRejectsUnexpectedPreferenceFiles() {
        val current = JSONObject().put("player_preferences", JSONObject()).put("series_skip_v1", JSONObject().put("show", "{intro:0,outro:0}"))
        assertEquals(current.toString(), migrateBackupPreferences(2, current, names).toString())
        current.put("untrusted-file", JSONObject())
        try { migrateBackupPreferences(2, current, names); fail("Unexpected file accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun oldBackupGetsEmptyMusicSupplementsAndAiSecretsStayForbidden() {
        val names = setOf("player_preferences", "series_skip_v1", "music_supplements_v1")
        val old = JSONObject().put("player_preferences", JSONObject()).put("series_skip_v1", JSONObject())
        val migrated = migrateBackupPreferences(2, old, names)
        assertEquals(0, migrated.getJSONObject("music_supplements_v1").length())
        val injected = JSONObject(migrated.toString()).put("nasplayer_ai_v1", JSONObject().put("api_key", "must-not-be-imported"))
        try { migrateBackupPreferences(3, injected, names); fail("API settings accepted") } catch (_: IllegalArgumentException) { }
    }
}
