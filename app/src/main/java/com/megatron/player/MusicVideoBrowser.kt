package com.megatron.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import com.megatron.player.source.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MusicVideoBrowser(category: MusicVideoCategory, repository: MusicVideoRepository, sources: List<MediaSourceConfig>,
                      sourceRevision: Int, modifier: Modifier = Modifier, onDirectories: () -> Unit, onPlay: (Uri, String) -> Unit,
                      headerContent: @Composable () -> Unit = {}) {
    val allRoots by repository.roots.collectAsState()
    val roots = allRoots[category].orEmpty()
    fun rootKey(root: MusicRoot) = SourceUris.build(root.sourceId, root.path).toString()
    var selected by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    val directoryStates = rememberSaveableStateHolder()
    fun directoryKey(chosen: MusicRoot?, location: String) = category.name + "\u0000" + chosen?.let(::rootKey).orEmpty() + "\u0000" + location
    var refresh by remember { mutableIntStateOf(0) }
    val root = roots.firstOrNull { rootKey(it) == selected } ?: roots.firstOrNull()
    val source = sources.firstOrNull { it.id == root?.sourceId }
    val browsePath = if (root != null && (selected != rootKey(root) || !(root.path.isBlank() || path == root.path || path.startsWith(root.path + "/")))) root.path else path
    var entries by remember(root?.sourceId, browsePath, sourceRevision, refresh, source?.address, source?.username) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember(root?.sourceId, browsePath, sourceRevision, refresh, source?.address, source?.username) { mutableStateOf(root != null) }
    var failure by remember(root?.sourceId, browsePath, sourceRevision, refresh, source?.address, source?.username) { mutableStateOf<String?>(null) }
    LaunchedEffect(root) { if (root != null && selected != rootKey(root)) { selected = rootKey(root); path = root.path } }
    LaunchedEffect(root?.sourceId, browsePath, sourceRevision, refresh, source?.address, source?.username) {
        entries = emptyList(); failure = null
        if (root == null) { loading = false; return@LaunchedEffect }
        loading = true
        try { entries = repository.listDirectory(root.sourceId, browsePath).filter { it.isDirectory || isVideoFile(it.name) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure = if (source == null) "视频连接已移除，请重新选择目录" else sourceErrorMessage(e) }
        finally { loading = false }
    }
    fun up() {
        val current = root ?: return
        val parent = browsePath.substringBeforeLast('/', "")
        path = parent.takeIf { current.path.isBlank() || it == current.path || it.startsWith(current.path + "/") } ?: current.path
    }
    BackHandler(root != null && browsePath != root.path) { up() }
    directoryStates.SaveableStateProvider(directoryKey(root, browsePath)) {
        var query by rememberSaveable { mutableStateOf("") }
        var sort by rememberSaveable { mutableStateOf("名称") }
        var menu by remember { mutableStateOf(false) }
        var searchOpen by remember { mutableStateOf(false) }
        var sortOpen by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        if (searchOpen) MediaListSearchDialog(query, "搜索当前目录", onSearch = {
            query = it; scope.launch { listState.scrollToItem(0) }
        }, onDismiss = { searchOpen = false })
        if (sortOpen) MediaListSortDialog(listOf("名称", "修改时间"), sort, onSelect = {
            sort = it; scope.launch { listState.scrollToItem(0) }
        }, onDismiss = { sortOpen = false })
        val filtered = entries.filter { it.name.contains(query, true) }.let { list ->
            val order = if (sort == "修改时间") compareBy<BrowserEntry> { !it.isDirectory }.thenByDescending { it.modifiedAt }.thenBy { it.name.lowercase(Locale.ROOT) }
                else compareBy<BrowserEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
            list.sortedWith(order)
        }
        Column(modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
                ChannelHeading("音乐") {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "${category.label}菜单") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("搜索") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; searchOpen = true })
                            DropdownMenuItem(text = { Text("排序") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { menu = false; sortOpen = true })
                            DropdownMenuItem(text = { Text("刷新") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, enabled = root != null, onClick = {
                                menu = false; root?.let { repository.invalidateDirectory(it.sourceId, browsePath) }; refresh++
                            })
                            DropdownMenuItem(text = { Text("${category.label}目录") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menu = false; onDirectories() })
                        }
                    }
                }
                headerContent()
            }
            // A short loading/error list would clamp a restored parent directory position.
            if (loading || failure != null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (root != null && browsePath != root.path) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::up) { Icon(Icons.Default.ArrowBack, "上一级视频目录") }
                        Text(browsePath.removePrefix(root.path).trimStart('/'), fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (loading) CircularProgressIndicator()
                    failure?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Row { TextButton(onClick = { root?.let { repository.invalidateDirectory(it.sourceId, browsePath) }; refresh++ }) { Text("重试") }; TextButton(onClick = onDirectories) { Text("管理目录") } }
                    }
                }
            } else MusicListScrollbar(listState, Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (roots.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 65.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(17.dp)) {
                            Icon(if (category == MusicVideoCategory.MV) Icons.Default.MusicVideo else Icons.Default.OndemandVideo, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("暂无${category.label}", fontSize = 20.sp)
                            Button(onClick = onDirectories) { Text("添加${category.label}目录") }
                        }
                    } else {
                        item {
                            if (roots.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                roots.forEach { r -> FilterChip(selected = r == root, onClick = { selected = rootKey(r); path = r.path }, label = { Text(r.name, fontSize = 11.sp, maxLines = 1) }) }
                            }
                            if (root != null && browsePath != root.path) Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::up) { Icon(Icons.Default.ArrowBack, "上一级视频目录") }
                                Text(browsePath.removePrefix(root.path).trimStart('/'), fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MediaListSearchFilter(query, onEdit = { searchOpen = true }, onClear = { query = "" })
                        }
                        items(filtered, key = { it.path }) { entry ->
                            ChannelRow(entry.name,
                                if (entry.isDirectory) null else entry.name.substringAfterLast('.', "").uppercase(Locale.ROOT) + if (entry.size > 0) " · %.1f MiB".format(Locale.ROOT, entry.size / 1048576.0) else "",
                                if (entry.isDirectory) Icons.Default.FolderOpen else Icons.Default.PlayCircleOutline,
                                onClick = { if (entry.isDirectory) { directoryStates.removeState(directoryKey(root, entry.path)); path = entry.path } else root?.let { onPlay(SourceUris.build(it.sourceId, entry.path), entry.name) } })
                        }
                        if (filtered.isEmpty()) item { Text(if (query.isBlank()) "当前目录没有视频或下级文件夹" else "当前目录没有匹配内容", Modifier.padding(vertical = 30.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
