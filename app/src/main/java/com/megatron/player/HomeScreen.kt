package com.megatron.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    history: List<RecentVideo>,
    onOpenLocal: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
    onDeleteRecent: (RecentVideo) -> Unit,
    onClearHistory: () -> Unit,
) {
    var url by rememberSaveable(stateSaver = HttpAddressValue.Saver) { mutableStateOf(HttpAddressValue()) }
    val focus = LocalFocusManager.current
    fun playUrl() { focus.clearFocus(); onOpenUrl(url.fullUrl()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NASPlayer", style = MaterialTheme.typography.headlineMedium)
                Text("${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = onOpenLocal, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                Icon(Icons.Default.FolderOpen, null)
                Text("  打开本地视频")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HttpAddressField(
                    value = url, onValueChange = { url = it },
                    label = "视频服务器与路径", placeholder = "nas.example.com/video.mkv",
                    imeAction = ImeAction.Go,
                    keyboardActions = KeyboardActions(onGo = { playUrl() }),
                )
                Button(onClick = ::playUrl, enabled = url.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("播放直链") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近播放", style = MaterialTheme.typography.titleLarge)
                if (history.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("清除记录") }
            }
        }
        if (history.isEmpty()) {
            item { Text("暂无播放记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(history, key = { it.uri }) { video ->
            Card(onClick = { onPlayRecent(video) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val progress = if (video.durationMs > 0) (video.positionMs.toDouble() * 100 / video.durationMs).toInt().coerceIn(0, 100) else null
                        Text(if (progress != null) "已播放 $progress%" else "可以继续观看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDeleteRecent(video) }) { Icon(Icons.Default.DeleteOutline, "删除此记录") }
                }
            }
        }
    }
}
