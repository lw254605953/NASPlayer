package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProgressSyncScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlexStore(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(store.progressSyncSnapshot()) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PlexPendingProgressInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { store.retryPendingProgress() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "同步未完成，请检查连接后重试。" }
            finally { entries = store.progressSyncSnapshot(); busy = false }
        }
    }
    fun resolve(item: PlexPendingProgressInfo, useLocal: Boolean) {
        busy = true; error = null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.resolveProgress(item.ratingKey, useLocal) }
                if (!saved) error = "处理结果未保存，请重试。"
                else if (useLocal) store.retryPendingProgress()
            } finally { entries = store.progressSyncSnapshot(); busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回个人设置") }
                Text("Plex 进度同步", Modifier.weight(1f), fontSize = 21.sp)
            }
            TextButton(onClick = ::refresh, enabled = !busy) { Text(if (busy) "正在核对…" else "立即重试并刷新") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (entries.isEmpty()) Text("当前连接没有待同步记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(entries, key = { it.ratingKey }) { item ->
            ChannelGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text("本机观看至 ${formatPlaybackTime(item.positionMs)} / ${formatPlaybackTime(item.durationMs)}", style = MaterialTheme.typography.bodySmall)
                    Text(item.message, style = MaterialTheme.typography.bodySmall, color = if (item.conflicted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.conflicted) {
                        TextButton(onClick = { resolve(item, false) }, enabled = !busy) { Text("保留服务器进度") }
                        TextButton(onClick = { confirm = item }, enabled = !busy) { Text("使用本机进度") }
                    }
                }
            }
        }
    }
    confirm?.let { item -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("使用本机进度？") },
        text = { Text("将「${item.title}」在 Plex 的观看进度更新为 ${formatPlaybackTime(item.positionMs)}，可能覆盖其他设备的记录。") },
        confirmButton = { TextButton(onClick = { confirm = null; resolve(item, true) }) { Text("确认使用本机") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
