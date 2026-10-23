package com.megatron.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.megatron.player.plex.PlexCredentials
import com.megatron.player.plex.PlexEpisodeViewMode
import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.PlexClient
import com.megatron.player.plex.plexEpisodeLabel

fun LazyListScope.plexEpisodePresentation(
    credentials: PlexCredentials,
    client: PlexClient,
    episodes: List<PlexMovie>,
    mode: PlexEpisodeViewMode,
    opening: String?,
    numberColumns: Int,
    onPlay: (PlexMovie) -> Unit,
    onSetWatched: ((PlexMovie, Boolean) -> Unit)? = null,
    watchBusyKey: String? = null,
) {
    when (mode) {
        PlexEpisodeViewMode.LIST -> episodes.forEach { episode ->
            item(key = "${mode.name}:${episode.ratingKey}") { PlexEpisodeMenuAnchor(episode, Modifier.fillMaxWidth(), opening, onPlay, onSetWatched, watchBusyKey) { PlexEpisodeListRow(credentials, client, episode, opening, onPlay) } }
        }
        PlexEpisodeViewMode.POSTER_TWO -> episodes.chunked(2).forEach { row ->
            item(key = "episode-poster2:${row.firstOrNull()?.ratingKey ?: "empty"}") { PlexEpisodePosterRow(credentials, client, row, 2, opening, onPlay, onSetWatched, watchBusyKey) }
        }
        PlexEpisodeViewMode.POSTER_FOUR -> episodes.chunked(4).forEach { row ->
            item(key = "episode-poster4:${row.firstOrNull()?.ratingKey ?: "empty"}") { PlexEpisodePosterRow(credentials, client, row, 4, opening, onPlay, onSetWatched, watchBusyKey) }
        }
        PlexEpisodeViewMode.NUMBERS -> episodes.chunked(numberColumns).forEach { row ->
            item(key = "episode-numbers:${row.firstOrNull()?.ratingKey ?: "empty"}") { PlexEpisodeNumberRow(row, numberColumns, opening, onPlay, onSetWatched, watchBusyKey) }
        }
    }
}

@Composable
private fun PlexEpisodeListRow(credentials: PlexCredentials, client: PlexClient, episode: PlexMovie, opening: String?, onPlay: (PlexMovie) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PlexMediaThumbnail(credentials, episode, client, Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), episodeProgress = true, compact = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${if (episode.index >= 0) "第${episode.index}集 · " else ""}${episode.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(episodeFacts(episode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (episode.summary.isNotBlank()) Text(episode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (opening == episode.ratingKey) Icons.Default.HourglassTop else Icons.Default.PlayCircleOutline, "播放 ${plexEpisodeLabel(episode)}", tint = MaterialTheme.colorScheme.primary)
    }
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
}

@Composable
private fun PlexEpisodePosterRow(credentials: PlexCredentials, client: PlexClient, row: List<PlexMovie>, columns: Int, opening: String?, onPlay: (PlexMovie) -> Unit, onSetWatched: ((PlexMovie, Boolean) -> Unit)?, watchBusyKey: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        row.forEach { episode ->
            PlexEpisodeMenuAnchor(episode, Modifier.weight(1f), opening, onPlay, onSetWatched, watchBusyKey) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlexMediaThumbnail(credentials, episode, client, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), episodeProgress = true, compact = columns >= 4)
                Text(plexEpisodeLabel(episode, includeSeason = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(episode.title, style = if (columns >= 4) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun PlexEpisodeNumberRow(row: List<PlexMovie>, columns: Int, opening: String?, onPlay: (PlexMovie) -> Unit, onSetWatched: ((PlexMovie, Boolean) -> Unit)?, watchBusyKey: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { episode ->
                    val watched = episode.watched
                    val inProgress = !watched && episode.viewOffsetMs > 0L
                    val color = when { watched -> MaterialTheme.colorScheme.primary; inProgress -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.surfaceVariant }
                    val textColor = when { watched -> MaterialTheme.colorScheme.onPrimary; inProgress -> MaterialTheme.colorScheme.onTertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    val content = when { watched -> "第${episode.index}集，已看"; inProgress -> "第${episode.index}集，观看至${formatPlaybackTime(episode.viewOffsetMs)}"; else -> "第${episode.index}集，未看" }
                    PlexEpisodeMenuAnchor(episode, Modifier.weight(1f), opening, onPlay, onSetWatched, watchBusyKey) {
                     androidx.compose.material3.Surface(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = content }, shape = RoundedCornerShape(10.dp), color = color) {
                        Box(contentAlignment = Alignment.Center) { Text(episode.index.takeIf { it >= 0 }?.toString() ?: "?", style = MaterialTheme.typography.titleMedium, color = textColor, modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1) }
                     }
                    }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun PlexEpisodeMenuAnchor(episode: PlexMovie, modifier: Modifier, opening: String?, onPlay: (PlexMovie) -> Unit, onSetWatched: ((PlexMovie, Boolean) -> Unit)?, watchBusyKey: String?, content: @Composable () -> Unit) {
    var menu by remember(episode.ratingKey) { mutableStateOf(false) }
    Box(modifier.combinedClickable(enabled = opening == null && watchBusyKey == null,
        onClick = { onPlay(episode) }, onLongClickLabel = "修改观看状态",
        onLongClick = if (onSetWatched != null) { { menu = true } } else null)) {
        content()
        if (watchBusyKey == episode.ratingKey) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp), strokeWidth = 2.dp)
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (episode.watched) "标为未看" else "标为已看") }, onClick = { menu = false; onSetWatched?.invoke(episode, !episode.watched) })
        }
    }
}

private fun episodeFacts(episode: PlexMovie): String = listOfNotNull(
    episode.durationMs.takeIf { it > 0 }?.let { "${it / 60_000} 分钟" },
    if (!episode.watched && episode.viewOffsetMs > 0) "观看至 ${formatPlaybackTime(episode.viewOffsetMs)}" else null,
).joinToString(" · ")
