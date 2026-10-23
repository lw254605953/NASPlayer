package com.megatron.player.backup

import org.json.JSONObject

/** 0.13.0 snapshots had no series overrides. Absence means global settings, never a forced zero. */
internal fun migrateBackupPreferences(schema: Int, preferences: JSONObject, allowed: Set<String>): JSONObject {
    require(schema in 1..3) { "不支持这个备份版本" }
    val expected = allowed - (if (schema == 1) setOf("series_skip_v1") else emptySet()) - (if (schema < 3) setOf("music_supplements_v1") else emptySet())
    require(preferences.keys().asSequence().toSet() == expected) { "备份配置不完整" }
    return JSONObject(preferences.toString()).apply { if (schema == 1) put("series_skip_v1", JSONObject()); if (schema < 3 && "music_supplements_v1" in allowed) put("music_supplements_v1", JSONObject()) }
}
