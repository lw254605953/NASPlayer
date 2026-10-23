package com.megatron.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import com.megatron.player.source.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(repository: MusicRepository, playback: MusicPlayback, state: MusicPlaybackState, sources: List<MediaSourceConfig>, sourceRevision: Int,
                modifier: Modifier = Modifier, onDirectories: () -> Unit, onTrackMenu: (MusicTrack) -> Unit,
                videos: MusicVideoRepository, ui: MusicUiState, onVideoDirectories: (MusicVideoCategory) -> Unit, onPlayVideo: (Uri, String) -> Unit) {
    val organizeContext = LocalContext.current
    val tracks by repository.tracks.collectAsState()
    val fileRevision by repository.fileRevision.collectAsState()
    val roots by repository.roots.collectAsState()
    val scanning by repository.scanning.collectAsState()
    val status by repository.status.collectAsState()
    val scanError by repository.error.collectAsState()
    val tabsScroll = rememberScrollState()
    var selectedSection by rememberSaveable { mutableStateOf("文件夹") }
    val section = selectedSection.takeIf { it in musicChannelSections } ?: "歌曲"
    var selectedRoot by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    val pageStates = rememberSaveableStateHolder()
    var handledReveal by rememberSaveable { mutableIntStateOf(0) }
    var pendingRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    fun rootKey(root: MusicRoot) = root.sourceId + "\u0000" + root.path
    fun pageKey(label: String, chosen: MusicRoot?, location: String) =
        label + "\u0000" + chosen?.let(::rootKey).orEmpty() + "\u0000" + location
    val root = roots.firstOrNull { rootKey(it) == selectedRoot } ?: roots.firstOrNull()
    LaunchedEffect(roots) { if (root != null && selectedRoot != rootKey(root)) { selectedRoot = rootKey(root); path = root.path } }
    val browsePath = if (root != null && selectedRoot != rootKey(root)) root.path else path
    val source = sources.firstOrNull { it.id == root?.sourceId }
    fun goUp() { if (root != null && browsePath != root.path) {
        pendingRevealId = null
        val parent = browsePath.substringBeforeLast('/', "")
        path = parent.takeIf { root.path.isBlank() || it == root.path || it.startsWith(root.path + "/") } ?: root.path
    } }
    BackHandler(section == "文件夹" && root != null && browsePath != root.path) { goUp() }
    val rootRequest = ui.browseRoot
    LaunchedEffect(rootRequest) {
        rootRequest?.let { chosen ->
            selectedSection = "文件夹"; selectedRoot = rootKey(chosen); path = chosen.path
            pendingRevealId = null; handledReveal = ui.revealPlayingTrack; ui.browseRoot = null
        }
    }
    LaunchedEffect(ui.revealPlayingTrack, rootRequest) {
        if (rootRequest == null && ui.revealPlayingTrack != handledReveal) {
            handledReveal = ui.revealPlayingTrack
            if (ui.revealPlayingTrack > 0 && !state.dismissed) state.current?.let { playing ->
                pendingRevealId = playing.id
                if (section != "歌曲") {
                    roots.filter { it.sourceId == playing.sourceId && (it.path.isBlank() || playing.path.startsWith(it.path.trimEnd('/') + "/")) }
                        .maxByOrNull { it.path.length }?.let { chosen ->
                            selectedSection = "文件夹"; selectedRoot = rootKey(chosen); path = playing.path.substringBeforeLast('/', "")
                        }
                }
            }
        }
    }
    val videoCategory = MusicVideoCategory.fromLabel(section)
    if (videoCategory != null) {
        pageStates.SaveableStateProvider("video:${videoCategory.name}") {
            MusicVideoBrowser(videoCategory, videos, sources, sourceRevision, modifier,
                onDirectories = { onVideoDirectories(videoCategory) }, onPlay = onPlayVideo,
                headerContent = { MusicSectionTabs(section, tabsScroll) { selectedSection = it; pendingRevealId = null } })
        }
        return
    }
    pageStates.SaveableStateProvider(pageKey(section, if (section == "文件夹") root else null, if (section == "文件夹") browsePath else "")) {
        var query by rememberSaveable { mutableStateOf("") }
        var sort by rememberSaveable { mutableStateOf("曲号") }
        var sortMenu by remember { mutableStateOf(false) }
        var searchOpen by remember { mutableStateOf(false) }
        var sourceMenu by remember { mutableStateOf(false) }
        val directoryKey = listOf(root?.sourceId.orEmpty(), source?.address.orEmpty(), source?.username.orEmpty(), browsePath).joinToString("\u0000")
        val previousEntries = ui.directoryPages.get(directoryKey, fileRevision, sourceRevision)
        var entries by remember { mutableStateOf(previousEntries.orEmpty()) }
        var loading by remember { mutableStateOf(root != null && section == "文件夹" && previousEntries == null) }
        var failure by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        if (searchOpen) MediaListSearchDialog(query, if (section == "文件夹") "搜索当前目录" else "搜索已索引音乐",
            onSearch = { query = it; scope.launch { listState.scrollToItem(0) } }, onDismiss = { searchOpen = false })
        if (sortMenu) MediaListSortDialog(listOf("曲号", "名称", "修改时间"), sort,
            onSelect = { sort = it; scope.launch { listState.scrollToItem(0) } }, onDismiss = { sortMenu = false })
        LaunchedEffect(section, root?.sourceId, browsePath, refresh, fileRevision, sourceRevision, source?.address, source?.username) {
            if (root == null || section != "文件夹") return@LaunchedEffect
            ui.directoryPages.get(directoryKey, fileRevision, sourceRevision)?.let {
                entries = it; loading = false; failure = null; return@LaunchedEffect
            }
            loading = true; failure = null
            try {
                entries = repository.listDirectory(root.sourceId, browsePath).filter { it.isDirectory || isMusicFile(it.name) }
                ui.directoryPages.put(directoryKey, fileRevision, sourceRevision, entries)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure = if (source == null) "音乐连接已移除，请重新选择音乐目录。" else sourceErrorMessage(e) }
            finally { loading = false }
        }
        val rootTracks = tracks.filter { track -> roots.any { it.sourceId == track.sourceId && (it.path.isBlank() || track.path.startsWith(it.path.trimEnd('/') + "/")) } }
        val allSongs = if (section == "文件夹") entries.filter { !it.isDirectory && repository.fileVisible(root?.sourceId.orEmpty(), it.path) }.map { repository.trackFor(root?.sourceId.orEmpty(), it) } else rootTracks
        val filtered = allSongs.filter { t ->
            query.isBlank() || listOf(t.title, t.artist, t.album, t.path.substringAfterLast('/')).any { it.contains(query, true) }
        }.let { list -> when (sort) {
            "名称" -> list.sortedBy { it.title.lowercase() }
            "修改时间" -> list.sortedByDescending { it.modifiedAt }
            else -> list.sortedWith(compareBy<MusicTrack> { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.path })
        } }
        val folders = if (section == "文件夹") entries.filter { it.isDirectory && it.name.contains(query, true) }.sortedBy { it.name.lowercase() } else emptyList()
        LaunchedEffect(pendingRevealId) { if (pendingRevealId != null) query = "" }
        LaunchedEffect(pendingRevealId, loading, filtered.map { it.id }, folders.size) {
            val requested = pendingRevealId
            if (requested != null && !loading && failure == null && query.isEmpty()) {
                val currentIndex = filtered.indexOfFirst { it.id == requested }
                if (currentIndex >= 0) {
                    val songHeaderCount = if (section == "文件夹") 1 else 2
                    listState.scrollToItem(songHeaderCount + folders.size + currentIndex)
                }
                pendingRevealId = null
            }
        }
        Column(modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
                ChannelHeading("音乐") {
                    Box {
                        IconButton(onClick = { sourceMenu = true }) { Icon(Icons.Default.MoreHoriz, "音乐菜单") }
                        DropdownMenu(sourceMenu, { sourceMenu = false }) {
                            DropdownMenuItem(text = { Text("搜索") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { sourceMenu = false; searchOpen = true })
                            DropdownMenuItem(text = { Text("排序") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { sourceMenu = false; sortMenu = true })
                            DropdownMenuItem(text = { Text("音乐目录") }, onClick = { sourceMenu = false; onDirectories() })
                            if (section == "文件夹" && root != null) DropdownMenuItem(text = { Text("刷新当前目录") }, onClick = {
                                sourceMenu = false; ui.directoryPages.invalidate(directoryKey); repository.invalidateDirectory(root.sourceId, browsePath); refresh++
                            })
                            DropdownMenuItem(text = { Text(if (scanning) "停止索引" else "刷新音乐索引") }, onClick = { sourceMenu = false; if (scanning) repository.cancelScan() else scope.launch { repository.scan() } })
                            HorizontalDivider()
                            DropdownMenuItem(text = { BetaFeatureLabel("资料整理记录") }, onClick = { sourceMenu = false; ui.organizeOpen = true })
                            DropdownMenuItem(text = { BetaFeatureLabel("整理音乐资料") }, onClick = { sourceMenu = false; com.megatron.player.music.metadata.MusicOrganizeEngine.shared(organizeContext).prepare(allSongs); ui.organizeOpen = true })
                        }
                    }
                }
                MusicSectionTabs(section, tabsScroll) { selectedSection = it; pendingRevealId = null }
            }
            // Keep restored list coordinates untouched until the directory contents are ready.
            if (section == "文件夹" && (loading || failure != null)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (root != null && browsePath != root.path) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::goUp) { Icon(Icons.Default.ArrowBack, "上一级音乐目录", Modifier.size(18.dp)) }
                        Text(browsePath.removePrefix(root.path).trimStart('/'), fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (loading) CircularProgressIndicator()
                    failure?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Row { TextButton(onClick = { ui.directoryPages.invalidate(directoryKey); root?.let { repository.invalidateDirectory(it.sourceId, browsePath) }; refresh++ }) { Text("重试") }; TextButton(onClick = onDirectories) { Text("选择音乐目录") } }
                    }
                }
            } else {
            MusicListScrollbar(listState, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            if (roots.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 65.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    Icon(Icons.Default.LibraryMusic, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("暂无音乐", fontSize = 20.sp)
                    Button(onClick = onDirectories) { Text("选择音乐目录") }
                }
            } else {
                item {
                    if (section == "文件夹") {
                        if (root != null && path != root.path) Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = ::goUp) { Icon(Icons.Default.ArrowBack, "上一级音乐目录", Modifier.size(18.dp)) }
                            Text(path.removePrefix(root.path).trimStart('/'), fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MediaListSearchFilter(query, onEdit = { searchOpen = true }, onClear = { query = "" })
                    state.error?.let { message ->
                        Text(message, Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        if (state.current != null) TextButton(onClick = { playback.toggle() }) { Text("重试当前歌曲") }
                    }
                    if (scanning || status != null || scanError != null) {
                        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(scanError ?: status.orEmpty(), Modifier.padding(top = 6.dp), fontSize = 11.sp, color = if (scanError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (scanError != null) TextButton(onClick = { repository.dismissError(); scope.launch { repository.scan() } }) { Text("重试索引") }
                    }
                }
                items(folders, key = { "folder:${it.path}" }) { folder ->
                    ChannelRow(folder.name, "文件夹", Icons.Default.FolderOpen, onClick = { pageStates.removeState(pageKey(section, root, folder.path)); path = folder.path; pendingRevealId = null })
                }
                if (section != "文件夹" && filtered.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { playback.play(filtered, filtered.first().id) }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.PlayArrow, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("播放全部", fontSize = 12.sp) }
                        Text("${filtered.size} 首", Modifier.weight(1f).padding(start = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(filtered, key = { "track:${it.id}" }) { track -> MusicTrackRow(track, !state.dismissed && state.current?.id == track.id,
                    { playback.play(filtered, track.id) }, { onTrackMenu(track) }) }
                if (filtered.isEmpty() && folders.isEmpty()) item {
                    if (section != "文件夹") MusicIndexEmpty(scanning, query) { scope.launch { repository.scan() } }
                    else Text(if (query.isBlank()) "当前目录没有音乐或下级文件夹" else "当前目录没有匹配内容", Modifier.padding(vertical = 30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        }
        }
        }
    }
}

@Composable
private fun MusicIndexEmpty(scanning: Boolean, query: String, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (query.isNotBlank()) "已索引音乐中没有匹配内容" else "暂无歌曲", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!scanning) TextButton(onClick = onScan) { Text("刷新音乐索引") }
    }
}

private val musicChannelSections = listOf("文件夹", "歌曲", "MV", "演唱会")

@Composable
private fun MusicSectionTabs(section: String, scroll: ScrollState, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(bottom = 15.dp), horizontalArrangement = Arrangement.spacedBy(23.dp)) {
        musicChannelSections.forEach { label ->
            Column(Modifier.clickable { onSelect(label) }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 14.sp, color = if (section == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(Modifier.width(if (label == "演唱会") 44.dp else 40.dp), color = if (section == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
            }
        }
    }
}
