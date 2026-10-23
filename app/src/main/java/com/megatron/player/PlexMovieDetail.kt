package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlexMovieDetail(credentials: PlexCredentials, movie: PlexMovie, client: PlexClient, showPlayback: Boolean = true, extraContent: LazyListScope.() -> Unit = {}, onSetWatched: ((PlexMovie, Boolean) -> Unit)? = null, watchBusy: Boolean = false, onPlay: (PlexMovie, PlexPart) -> Unit) {
    var seriesSettings by remember { mutableStateOf(false) }
    val seriesKey = if (movie.type == "show" && credentials.config.machineIdentifier.isNotBlank() && movie.ratingKey.isNotBlank())
        SeriesSkipKey(credentials.config.machineIdentifier, movie.ratingKey) else null
    if (seriesSettings && seriesKey != null) SeriesSkipDialog(seriesKey) { seriesSettings = false }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlexMediaThumbnail(credentials, movie, client, Modifier.width(106.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(13.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(movie.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    if (movie.type in setOf("movie", "show")) PlexFavoriteButton(credentials.config, movie)
                    if (seriesKey != null) OutlinedButton(onClick = { seriesSettings = true }) { Text("片头片尾") }
                    if (movie.originalTitle.isNotBlank() && !movie.originalTitle.equals(movie.title, true)) Text(movie.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(movie.year.takeIf { it > 0 }?.toString(), movie.durationMs.takeIf { it > 0 }?.let { "${it / 60_000} 分钟" }).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (movie.genres.isNotEmpty()) Text(movie.genres.joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            if (movie.ratings.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                movie.ratings.forEach { score ->
                    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(score.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${String.format(Locale.ROOT, "%.1f", score.value)} / 10", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else Text("Plex 暂未提供评分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showPlayback) item {
            val part = movie.parts.firstOrNull()
            Button(onClick = { part?.let { onPlay(movie, it) } }, enabled = part != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Text(if (movie.viewOffsetMs > 0 && !movie.watched) "  从 ${formatPlaybackTime(movie.viewOffsetMs)} 继续" else "  播放影片")
            }
            if (part == null) Text("暂无可播放文件", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (part != null && movie.viewOffsetMs > 0 && !movie.watched) TextButton(onClick = { onPlay(movie.copy(viewOffsetMs = 0, playFromStart = true), part) }) { Text("从头播放") }
            if (movie.parts.size > 1) Text("${movie.parts.size} 个版本", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            part?.let { Text(listOf(it.container.uppercase(), it.height.takeIf { h -> h > 0 }?.let { h -> "${h}p" }.orEmpty(), it.videoCodec.uppercase(), it.audioCodec.uppercase()).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onSetWatched != null && movie.type in setOf("movie", "episode")) item {
            OutlinedButton(onClick = { onSetWatched(movie, !movie.watched) }, enabled = !watchBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                if (watchBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (movie.watched) Icons.Default.Undo else Icons.Default.CheckCircleOutline, null)
                Spacer(Modifier.width(8.dp)); Text(if (watchBusy) "正在同步…" else if (movie.watched) "标为未看" else "标为已看")
            }
        }
        if (movie.tagline.isNotBlank()) item { Text(movie.tagline, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.primary) }
        if (movie.summary.isNotBlank()) item { DetailHeading("简介"); Text(movie.summary, fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        extraContent()
        if (movie.cast.isNotEmpty()) item {
            DetailHeading("演员")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(movie.cast) { person ->
                    Column(Modifier.width(82.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        PlexPoster(credentials, person.thumb, client, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                            description = "${person.name}头像", placeholder = Icons.Default.Person,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop, portrait = true)
                        Text(person.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (person.role.isNotBlank()) Text(person.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            DetailHeading("影片资料")
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    DetailValue("导演", movie.directors.joinToString(" / "))
                    DetailValue("编剧", movie.writers.joinToString(" / "))
                    DetailValue("国家 / 地区", movie.countries.joinToString(" / "))
                    DetailValue("制片方", movie.studio)
                    DetailValue("上映日期", movie.releaseDate)
                    DetailValue("最近观看", movie.lastViewedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance().format(Date(it * 1000)) }.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun DetailHeading(value: String) { Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp)) }

@Composable
private fun DetailValue(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
