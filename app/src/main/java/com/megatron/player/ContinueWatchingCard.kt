package com.megatron.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException

/** Prefer a cached series poster without issuing an extra metadata request on Home. */
@Composable
fun ContinueWatchingPoster(credentials: PlexCredentials, movie: PlexMovie, session: PlexBrowseSession, modifier: Modifier) {
    var seriesThumb by remember(credentials.config.id, credentials.config.address, movie.ratingKey) { mutableStateOf("") }
    LaunchedEffect(credentials.config.id, credentials.config.address, movie.ratingKey, session.revision) {
        seriesThumb = if (movie.type == "episode" && movie.grandparentRatingKey.isNotBlank()) {
            try { session.cachedDetail(movie.grandparentRatingKey)?.thumb.orEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "" }
        } else ""
    }
    val title = if (movie.type == "episode") movie.grandparentTitle.ifBlank { movie.title } else movie.title
    PlexPoster(credentials, seriesThumb.ifBlank { movie.thumb }, session.client, modifier, description = "${title}海报")
}

/** The same real-metadata resume card on Home and in the complete continue list. */
@Composable
fun ContinueWatchingCard(
    title: String,
    subtitle: String,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    playEnabled: Boolean = true,
    onDetails: (() -> Unit)? = null,
    detailsLabel: String = "影片详情",
    poster: (@Composable (Modifier) -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .055f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = Modifier.width(70.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp))
            if (poster != null) poster(art) else Box(art.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, "视频暂无海报", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(continueTimeLabel(positionMs, durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (durationMs > 0) {
                    val progress = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
                    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f))
                        .semantics { contentDescription = "播放进度 ${(progress * 100).toInt()}%" }) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
                onDetails?.let { action ->
                    TextButton(onClick = action, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(detailsLabel, fontSize = 11.sp)
                    }
                }
            }
            IconButton(onClick = onPlay, enabled = playEnabled && !busy, modifier = Modifier.size(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PlayArrow, "继续播放 $title", Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun continueTimeLabel(position: Long, duration: Long): String {
    val watched = if (position > 0) "观看至 ${formatPlaybackTime(position)}" else "下一集"
    if (duration <= 0 || duration <= position) return watched
    val minutes = ((duration - position + 59_999L) / 60_000L).coerceAtLeast(1L)
    return "$watched · 剩余 ${if (minutes >= 60) "${minutes / 60}小时${if (minutes % 60 > 0) "${minutes % 60}分" else ""}" else "${minutes}分钟"}"
}
