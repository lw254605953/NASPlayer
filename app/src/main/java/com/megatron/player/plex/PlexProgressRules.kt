package com.megatron.player.plex

/** Exact checkpoints intentionally preserve rewinds/restarts; position is never merged with max(). */
internal data class PlexProgressCheckpoint(val positionMs: Long, val watched: Boolean, val lastViewedAt: Long)

internal object PlexProgressRules {
    fun mayReplay(baseline: PlexProgressCheckpoint?, remote: PlexProgressCheckpoint, forceLocal: Boolean): Boolean =
        forceLocal || (baseline != null && baseline.lastViewedAt > 0 && remote.lastViewedAt > 0 && baseline == remote)
}
