package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*

/** One watch marker and progress treatment for library, detail and episode art. */
@Composable
fun PlexMediaThumbnail(
    credentials: PlexCredentials,
    movie: PlexMovie,
    client: PlexClient,
    modifier: Modifier = Modifier,
    episodeProgress: Boolean = false,
    compact: Boolean = false,
) {
    Box(modifier) {
        PlexPoster(credentials, movie.thumb, client, Modifier.matchParentSize())
        val progress = plexThumbnailProgress(movie, episodeProgress)
        if (progress != null) {
            val label = if (movie.type == "episode") plexEpisodeLabel(movie) else movie.title
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                    .semantics { contentDescription = "$label，播放进度 ${(progress * 100).toInt()}%" },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = .28f),
            )
        }
        if (movie.watched) {
            Box(Modifier.align(Alignment.TopEnd).padding(if (compact) 3.dp else 6.dp)
                .size(if (compact) 18.dp else 22.dp).background(Color.Black.copy(alpha = .72f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, "已看", Modifier.size(if (compact) 13.dp else 16.dp), tint = Color.White)
            }
        }
    }
}
