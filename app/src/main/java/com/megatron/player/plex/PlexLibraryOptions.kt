package com.megatron.player.plex

enum class PlexSort(val label: String, val field: String, val defaultDescending: Boolean) {
    RELEASED("上映日期", "originallyAvailableAt", true),
    TITLE("片名", "titleSort", false),
    YEAR("年份", "year", true),
    RATING("影评评分", "rating", true),
    AUDIENCE_RATING("观众评分", "audienceRating", true),
    DURATION("片长", "duration", true),
    LAST_VIEWED("最近观看", "lastViewedAt", true),
}

enum class PlexViewMode(val label: String, val columns: Int) {
    POSTER_TWO("海报 · 每行2部", 2), POSTER_FOUR("海报 · 每行4部", 4), LIST("列表", 1),
}

data class PlexLibraryOptions(
    val sort: PlexSort = PlexSort.RELEASED,
    val descending: Boolean = true,
    val view: PlexViewMode = PlexViewMode.POSTER_FOUR,
)

fun updatedSearchHistory(existing: List<String>, query: String, limit: Int = 12): List<String> {
    val cleaned = query.trim().take(100)
    if (cleaned.isEmpty()) return existing
    return (listOf(cleaned) + existing.filterNot { it.equals(cleaned, ignoreCase = true) }).take(limit)
}

/** Values come from Plex's section facets; filtering is always performed by the server. */
enum class PlexWatchFilter(val label: String) { ALL("全部状态"), UNWATCHED("未看"), WATCHED("已看") }
data class PlexLibraryFilter(
    val watched: PlexWatchFilter = PlexWatchFilter.ALL,
    val genre: String = "",
    val year: String = "",
    val minimumRating: Int = 0,
) : java.io.Serializable {
    val active: Boolean get() = watched != PlexWatchFilter.ALL || genre.isNotBlank() || year.isNotBlank() || minimumRating > 0
}
data class PlexFacet(val key: String, val label: String)
data class PlexLibraryFacets(val genres: List<PlexFacet> = emptyList(), val years: List<PlexFacet> = emptyList())
