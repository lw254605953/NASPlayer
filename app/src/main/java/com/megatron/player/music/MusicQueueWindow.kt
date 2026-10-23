package com.megatron.player.music

internal data class MusicQueueWindow(val tracks: List<MusicTrack>, val startIndex: Int)

/** Each directory selection creates a bounded queue around the requested track, never a prefix that omits it. */
internal fun musicQueueWindow(tracks: List<MusicTrack>, startId: String, maxItems: Int = 500, maxBytes: Long = 400_000): MusicQueueWindow? {
    require(maxItems > 0 && maxBytes > 0)
    val distinct = tracks.distinctBy { it.id }
    val selected = distinct.indexOfFirst { it.id == startId }
    if (selected < 0) return null
    var first = (selected - maxItems / 2).coerceIn(0, (distinct.size - maxItems).coerceAtLeast(0))
    var end = minOf(first + maxItems, distinct.size)
    val sizes = (first until end).associateWith { distinct[it].json().toString().toByteArray(Charsets.UTF_8).size.toLong() }
    var bytes = sizes.values.sum()
    // Exceptionally large optional tags narrow the window instead of refusing the requested song.
    while (bytes > maxBytes && end - first > 1) {
        if (selected - first > end - selected - 1) bytes -= sizes.getValue(first++)
        else bytes -= sizes.getValue(--end)
    }
    return MusicQueueWindow(distinct.subList(first, end), selected - first)
}
