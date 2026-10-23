package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*

@Composable
fun PlexFavoriteRow(item: PlexFavorite, session: PlexBrowseSession, onOpen: () -> Unit, onRemove: () -> Unit) {
    val credentials = session.credentials?.takeIf { item.belongsTo(it.config) }
    val movie = item.movie
    ChannelGroup {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val art = Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            if (credentials != null) PlexPoster(credentials, movie.thumb, session.client, art, description = "${movie.title}海报")
            else Box(art.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(if (movie.type == "show") Icons.Default.Tv else Icons.Default.Movie, null)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(movie.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (movie.type == "show") "电视剧" else "电影", movie.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(credentials?.config?.name ?: "${item.serverName} · 片源未连接", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Favorite, "取消影片收藏 ${movie.title}", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}
