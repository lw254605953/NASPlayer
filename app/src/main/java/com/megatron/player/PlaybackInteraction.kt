package com.megatron.player

import java.util.Locale
import kotlin.math.roundToLong

fun parsePlaybackTimeMs(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 1..3 || parts.any { it.isEmpty() || it.any { ch -> ch !in '0'..'9' } }) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.size >= 2 && values.last() >= 60) return null
    if (values.size == 3 && values[1] >= 60) return null
    val maxSeconds = Long.MAX_VALUE / 1000
    var seconds = 0L
    for (value in values) {
        if (value > maxSeconds || seconds > (maxSeconds - value) / 60) return null
        seconds = seconds * 60 + value
    }
    return seconds * 1000
}

fun formatPlaybackTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

fun gestureSeekPositionMs(startMs: Long, durationMs: Long, distancePx: Float, widthPx: Int): Long? {
    if (durationMs <= 0 || widthPx <= 0 || !distancePx.isFinite()) return null
    val span = minOf(durationMs, 120_000L)
    return (startMs.toDouble() + distancePx.toDouble() / widthPx * span)
        .coerceIn(0.0, durationMs.toDouble()).roundToLong()
}
