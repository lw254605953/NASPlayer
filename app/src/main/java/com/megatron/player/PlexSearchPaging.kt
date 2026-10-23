package com.megatron.player

import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.PlexMoviePage

internal data class PlexSearchGroup(val movies: List<PlexMovie> = emptyList(), val total: Int = 0,
    val nextOffset: Int = 0, val loading: Boolean = false, val failure: String? = null)

/** Reject ignored/overlapping server pages rather than silently losing results or looping forever. */
internal fun mergePlexSearchPage(previous: PlexSearchGroup, page: PlexMoviePage, requestedOffset: Int): PlexSearchGroup {
    check(page.offset == requestedOffset && requestedOffset >= 0) { "Plex 搜索分页偏移异常" }
    check(page.movies.isNotEmpty() || page.total <= requestedOffset) { "Plex 搜索分页提前结束" }
    val keys = page.movies.map { it.ratingKey }
    check(keys.none { it.isBlank() } && keys.distinct().size == keys.size) { "Plex 搜索分页标识重复" }
    if (requestedOffset > 0) {
        check(requestedOffset == previous.nextOffset) { "Plex 搜索分页未衔接" }
        val seen = previous.movies.map { it.ratingKey }.toHashSet()
        check(keys.none { it in seen }) { "Plex 搜索分页重复" }
    }
    return PlexSearchGroup(if (requestedOffset == 0) page.movies else previous.movies + page.movies,
        page.total, requestedOffset + page.movies.size)
}
