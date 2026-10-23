package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun historyCompleted(video: RecentVideo): Boolean = video.durationMs > 0 && video.positionMs.toDouble() / video.durationMs >= .95

@Composable
fun WatchHistoryScreen(history: List<RecentVideo>, modifier: Modifier = Modifier, onPlay: (RecentVideo) -> Unit,
    onRemove: (RecentVideo) -> Unit, onBack: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    val visible = history.sortedByDescending { it.updatedAt }.filter {
        when (filter) { "已看完" -> historyCompleted(it); "未看完" -> !historyCompleted(it); else -> true }
    }
    val zone = ZoneId.systemDefault()
    val days = visible.groupBy { if (it.updatedAt > 0) Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate().toString() else "更早记录" }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回个人设置") }
                Text("观看历史", fontSize = 21.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "未看完", "已看完").forEach { mode -> FilterChip(filter == mode, onClick = { filter = mode }, label = { Text(mode) }) }
            }
        }
        if (visible.isEmpty()) item { Text("暂无${if (filter == "全部") "" else filter}观看记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        days.forEach { (day, records) ->
            item(key = "day:$day") { ChannelLabel(day) }
            records.forEach { recent -> item(key = recent.uri) {
                ChannelGroup {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(recent.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { onRemove(recent) }) { Icon(Icons.Default.Close, "删除记录 ${recent.title}") }
                        }
                        val stamp = recent.updatedAt.takeIf { it > 0 }?.let { DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()
                        Text(listOf(stamp, if (historyCompleted(recent)) "已看完" else "观看至 ${formatPlaybackTime(recent.positionMs)}",
                            recent.durationMs.takeIf { it > 0 }?.let { "共 ${formatPlaybackTime(it)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (recent.durationMs > 0) LinearProgressIndicator(progress = { (recent.positionMs.toFloat() / recent.durationMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 10.dp))
                        TextButton(onClick = { onPlay(recent) }) { Text(if (historyCompleted(recent)) "重新播放" else "继续播放") }
                    }
                }
            } }
        }
    }
}
