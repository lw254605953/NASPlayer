package com.megatron.player.plex

/** Small, network-free rules shared by the series browser and the player. */
fun plexPlaybackTitle(movie: PlexMovie): String {
    if (!movie.type.equals("episode", ignoreCase = true) || movie.parentIndex < 0 || movie.index < 0) return movie.title
    val show = movie.grandparentTitle.ifBlank { movie.parentTitle }
    val number = plexEpisodeLabel(movie)
    return listOf(show, number, movie.title).filter { it.isNotBlank() }.joinToString(" · ")
}

/** User-facing numbering matches the season selector and episode list. */
fun plexEpisodeLabel(episode: PlexMovie, includeSeason: Boolean = true): String {
    if (episode.index < 0) return episode.title
    val number = "第${episode.index}集"
    if (!includeSeason || episode.parentIndex < 0) return number
    val season = if (episode.parentIndex == 0) "特别篇" else "第${episode.parentIndex}季"
    return "$season · $number"
}

/** A missing duration/count is unknown, not a fabricated zero percent. */
fun plexWatchProgress(movie: PlexMovie): Float? = when {
    movie.watched -> 1f
    movie.type == "show" || movie.type == "season" -> movie.leafCount.takeIf { it > 0 }
        ?.let { (movie.viewedLeafCount.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    movie.durationMs > 0 -> (movie.viewOffsetMs.toDouble() / movie.durationMs).coerceIn(0.0, 1.0).toFloat()
    else -> null
}

/** No empty progress track on artwork that has no playback progress. */
fun plexThumbnailProgress(movie: PlexMovie, episodeProgress: Boolean = false): Float? =
    plexWatchProgress(movie)?.takeIf { it > 0f && (episodeProgress || !movie.watched) }

private fun PlexMovie.isEpisode() = type.equals("episode", ignoreCase = true)
private fun PlexMovie.isSpecial() = isEpisode() && parentIndex == 0
private fun PlexMovie.seasonOrder() = if (parentIndex >= 0) parentIndex else Int.MAX_VALUE
private fun PlexMovie.episodeOrder() = if (index >= 0) index else Int.MAX_VALUE

/** De-duplicates by ratingKey and provides deterministic season/episode order. */
fun orderedPlexEpisodes(items: List<PlexMovie>): List<PlexMovie> {
    val unique = LinkedHashMap<String, PlexMovie>()
    items.filter { it.isEpisode() }.forEach { unique.putIfAbsent(it.ratingKey, it) }
    return unique.values.sortedWith(compareBy<PlexMovie> { it.seasonOrder() }.thenBy { it.episodeOrder() }.thenBy { it.ratingKey })
}

private fun PlexMovie.isCompleteByPosition(): Boolean = durationMs > 0L && viewOffsetMs.toDouble() / durationMs >= 0.95
private fun PlexMovie.isUnfinishedWithProgress(): Boolean = !watched && viewOffsetMs > 0L && !isCompleteByPosition()

/** Picks a normal-season resume target; specials never take over normal playback. */
fun resumePlexEpisode(items: List<PlexMovie>): PlexMovie? {
    val normal = orderedPlexEpisodes(items).filterNot { it.isSpecial() }
    if (normal.isEmpty()) return null
    return normal.filter { it.isUnfinishedWithProgress() }
        .maxWithOrNull(compareBy<PlexMovie> { it.lastViewedAt }.thenBy { it.seasonOrder() }.thenBy { it.episodeOrder() })
        ?: normal.firstOrNull { !it.watched && !it.isCompleteByPosition() }
        ?: normal.first()
}

/** Finds the adjacent episode. Normal seasons flow across season boundaries. */
fun adjacentPlexEpisode(items: List<PlexMovie>, currentKey: String, direction: Int): PlexMovie? {
    if (direction == 0) return null
    val current = items.firstOrNull { it.ratingKey == currentKey && it.isEpisode() } ?: return null
    val step = if (direction > 0) 1 else -1
    val candidates = if (current.isSpecial()) {
        orderedPlexEpisodes(items).filter { it.parentIndex == current.parentIndex }
    } else {
        orderedPlexEpisodes(items).filterNot { it.isSpecial() }
    }
    val index = candidates.indexOfFirst { it.ratingKey == currentKey }
    return candidates.getOrNull(index + step)
}
