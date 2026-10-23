package com.megatron.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

fun musicTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

fun musicPositionText(positionMs: Long, durationMs: Long): String =
    musicTime(positionMs) + " / " + if (durationMs > 0) musicTime(durationMs) else "--:--"

fun musicModeLabel(mode: MusicMode) = when (mode) {
    MusicMode.SEQUENCE -> "顺序播放"
    MusicMode.LOOP -> "列表循环"
    MusicMode.ONE -> "单曲循环"
    MusicMode.SHUFFLE -> "随机播放"
}

fun musicTrackSubtitle(track: MusicTrack) = listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · ")
fun musicAudioInfo(track: MusicTrack) = listOf(
    track.format,
    track.bitDepth.takeIf { it > 0 }?.let { "$it bit" }.orEmpty(),
    track.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" }.orEmpty(),
).filter(String::isNotBlank).joinToString(" · ")

@Composable
fun MusicArtwork(track: MusicTrack?, modifier: Modifier = Modifier) {
    val path = track?.artworkPath
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                MusicArtworkMemory.get(path)?.let { return@runCatching it.asImageBitmap() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
                    inSampleSize = sample
                }
                BitmapFactory.decodeFile(path, options)?.also { MusicArtworkMemory.put(path, it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, track?.album?.takeIf(String::isNotBlank) ?: "歌曲封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.45f), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
    }
}

@Composable
fun MusicTrackRow(track: MusicTrack, playing: Boolean, onPlay: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics { selected = playing }.background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else androidx.compose.ui.graphics.Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onPlay).heightIn(min = 77.dp).padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArtwork(track, Modifier.size(47.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(listOf(track.format, musicTrackSubtitle(track)).filter(String::isNotBlank).joinToString(" · ").ifEmpty { track.path.substringAfterLast('/') },
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playing) Icon(Icons.Default.GraphicEq, "当前歌曲", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onMore, Modifier.size(44.dp)) { Icon(Icons.Default.MoreHoriz, "${track.title}更多操作", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f))
}

@Composable
fun MusicMiniPlayer(state: MusicPlaybackState, playback: MusicPlayback, onOpen: () -> Unit, onQueue: () -> Unit) {
    val track = state.current ?: return
    val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.padding(start = 10.dp, end = 5.dp, top = 7.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    MusicArtwork(track, Modifier.size(42.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(track.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.error?.let { "播放遇到问题，点此查看" } ?: track.artist.ifBlank { track.format }, Modifier.weight(1f), fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(musicPositionText(state.positionMs, duration), fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = { playback.toggle() }) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "暂停音乐" else "继续音乐") }
                IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, "播放队列") }
            }
            LinearProgressIndicator(progress = { if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

private object MusicArtworkMemory : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
}
