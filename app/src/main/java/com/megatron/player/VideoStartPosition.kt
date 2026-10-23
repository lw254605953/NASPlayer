package com.megatron.player

/** A user-selected bookmark must win over history and the completed-video reset rule, including zero. */
fun videoStartPosition(record: RecentVideo, exactPosition: Boolean): Long =
    if (exactPosition) record.positionMs.coerceAtLeast(0L) else resumePositionMs(record.positionMs, record.durationMs)
