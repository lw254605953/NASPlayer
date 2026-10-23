package com.megatron.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import com.megatron.player.source.*
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicDirectoryPicker(sources: List<MediaSourceConfig>, repository: MusicRepository, onDismiss: () -> Unit, onAddSource: () -> Unit, onOpenRoot: ((MusicRoot) -> Unit)? = null) {
    val roots by repository.roots.collectAsState()
    MediaDirectoryPicker(sources, roots, "音乐", repository::listDirectory, repository::saveRoots, onDismiss, onAddSource, onOpenRoot)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDirectoryPicker(sources: List<MediaSourceConfig>, roots: List<MusicRoot>, label: String,
                         listDirectory: suspend (String, String) -> List<BrowserEntry>, saveRoots: (List<MusicRoot>) -> Unit,
                         onDismiss: () -> Unit, onAddSource: () -> Unit, onOpenRoot: ((MusicRoot) -> Unit)? = null) {
    var sourceId by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val directoryState = rememberLazyListState()
    val config = sources.firstOrNull { it.id == sourceId }
    LaunchedEffect(sourceId, path, refresh, config?.address, config?.username) {
        if (sourceId == null) return@LaunchedEffect
        loading = true; entries = emptyList(); error = null
        try { entries = listDirectory(sourceId!!, path).filter { it.isDirectory } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = sourceErrorMessage(e) }
        finally { loading = false }
    }
    fun back() { if (path.isNotBlank()) path = path.substringBeforeLast('/', "") else sourceId = null }
    BackHandler(sourceId != null) { back() }
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 22.dp).heightIn(max = 610.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (sourceId != null) IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, "上一级") }
                Text(if (sourceId == null) "${label}目录" else "选择${label}目录", Modifier.weight(1f), fontSize = 20.sp)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭${label}目录") }
            }
            if (sourceId == null) {
                MusicListScrollbar(listState, Modifier.weight(1f, fill = false)) {
                LazyColumn(state = listState) {
                    if (roots.isNotEmpty()) item { ChannelLabel("已选择") }
                    items(roots, key = { it.sourceId + ":" + it.path }) { root ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = onOpenRoot != null) { onOpenRoot?.invoke(root) }.padding(vertical = 13.dp)) {
                                Text(root.name, fontSize = 13.sp)
                                Text((sources.firstOrNull { it.id == root.sourceId }?.name ?: "连接已移除") + " / " + root.path.ifBlank { "/" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { saveRoots(roots.filterNot { it == root }) }) { Icon(Icons.Default.RemoveCircleOutline, "移除${label}目录 ${root.name}") }
                        }
                    }
                    item { ChannelLabel("从已保存的 WebDAV 添加") }
                    items(sources.filter { it.kind == SourceKind.WEBDAV }, key = { it.id }) { source ->
                        ChannelRow(source.name, null, Icons.Default.Dns, onClick = { sourceId = source.id; path = "" })
                    }
                    item { TextButton(onClick = onAddSource) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("添加 WebDAV 连接") } }
                }
                }
            } else {
                Text("${config?.name ?: "连接已移除"} / ${path.ifBlank { "/" }}", Modifier.padding(vertical = 12.dp), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp); TextButton(onClick = { refresh++ }) { Text("重试读取目录") } }
                MusicListScrollbar(directoryState, Modifier.weight(1f, fill = false)) {
                LazyColumn(state = directoryState) {
                    items(entries, key = { it.path }) { entry ->
                        ChannelRow(entry.name, icon = Icons.Default.FolderOpen, onClick = { path = entry.path })
                    }
                    if (entries.isEmpty() && !loading && error == null) item { Text("暂无子目录", Modifier.padding(vertical = 25.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                }
                Button(onClick = {
                    val selected = MusicRoot(sourceId!!, path, path.substringAfterLast('/').ifBlank { config?.name ?: label })
                    saveRoots((roots + selected).distinctBy { it.sourceId + "\u0000" + it.path })
                    onDismiss()
                }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !loading && error == null && config != null) { Text("选择当前目录") }
            }
        }
    }
}
