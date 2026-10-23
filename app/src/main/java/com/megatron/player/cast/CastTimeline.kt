package com.megatron.player.cast

/** Unknown duration must not erase a duration already supplied by the source. */
internal fun castDurationMs(reported: Long, known: Long): Long = reported.takeIf { it > 0 } ?: known.coerceAtLeast(0)
