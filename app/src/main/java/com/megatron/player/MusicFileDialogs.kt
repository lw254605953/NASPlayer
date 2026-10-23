package com.megatron.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import com.megatron.player.source.BrowserEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicFileDialogs(repository: MusicRepository, playback: MusicPlayback, ui: MusicUiState) {
    val track = ui.deleteFile ?: ui.moveFile ?: return
    val deleting = ui.deleteFile != null
    var path by remember(track.id, deleting) { mutableStateOf(track.path.substringBeforeLast('/', "")) }
    var entries by remember(track.id) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun dismiss() { if (!busy) { ui.deleteFile = null; ui.moveFile = null } }
    fun execute() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                playback.mutateFile(repository, track, if (deleting) null else path)
                ui.deleteFile = null; ui.moveFile = null
            } catch (e: Exception) { error = e.message ?: "文件操作失败，请刷新目录核实" }
            finally { busy = false }
        }
    }
    LaunchedEffect(track.id, deleting, path, retry) {
        if (deleting) return@LaunchedEffect
        loading = true; error = null; entries = emptyList()
        try { entries = repository.listDirectory(track.sourceId, path).filter { it.isDirectory }; scroll.scrollToItem(0) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = sourceErrorMessage(e) }
        finally { loading = false }
    }
    BackHandler(busy) { }
    if (deleting) {
        AlertDialog(onDismissRequest = ::dismiss, title = { Text("删除音乐文件？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(track.path.substringAfterLast('/'))
                Text("将从 NAS 永久删除此文件，无法撤销。", fontSize = 13.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            } },
            confirmButton = { TextButton(onClick = ::execute, enabled = !busy) { Text(if (busy) "删除中…" else "删除文件", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = ::dismiss, enabled = !busy) { Text("取消") } })
    } else {
        AppBottomSheet(onDismissRequest = ::dismiss) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotBlank()) IconButton(onClick = { path = path.substringBeforeLast('/', "") }, enabled = !busy) { Icon(Icons.Default.ArrowBack, "上一级移动目录") }
                    Text("移动音乐文件", Modifier.weight(1f), fontSize = 20.sp)
                    IconButton(onClick = ::dismiss, enabled = !busy) { Icon(Icons.Default.Close, "取消移动文件") }
                }
                Text(track.path.substringAfterLast('/'), Modifier.padding(vertical = 12.dp), fontSize = 13.sp)
                Text("/${path.trim('/')}", Modifier.padding(bottom = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (!busy) TextButton(onClick = { repository.invalidateDirectory(track.sourceId, path); retry++ }) { Text("刷新目录") } }
                MusicListScrollbar(scroll, Modifier.weight(1f, fill = false)) {
                    LazyColumn(state = scroll) {
                        items(entries, key = { it.path }) { directory ->
                            ChannelRow(directory.name, icon = Icons.Default.FolderOpen, onClick = { if (!busy) path = directory.path })
                        }
                        if (entries.isEmpty() && !loading) item { Text("暂无子目录", Modifier.padding(vertical = 20.dp), fontSize = 12.sp) }
                    }
                }
                Button(onClick = ::execute, enabled = !busy && !loading && path != track.path.substringBeforeLast('/', ""), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(if (busy) "移动中…" else "移动到此目录")
                }
            }
        }
    }
}
