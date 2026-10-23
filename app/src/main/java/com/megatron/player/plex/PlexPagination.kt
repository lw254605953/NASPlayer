package com.megatron.player.plex

/** Advance by the server page, never by the number of deduplicated cards on screen. */
internal fun nextPlexPageOffset(page: PlexMoviePage, requestedOffset: Int): Int? {
    check(page.offset == requestedOffset) { "Plex 分页偏移异常，请刷新当前库" }
    if (page.movies.isEmpty()) return null
    val next = page.offset.toLong() + page.movies.size
    return next.takeIf { it < page.total && it <= Int.MAX_VALUE }?.toInt()
}
