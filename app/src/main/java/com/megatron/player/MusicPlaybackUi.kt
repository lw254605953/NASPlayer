package com.megatron.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*

@Stable
class MusicUiState {
    val directoryPages = MusicDirectoryMemory()
    var revealPlayingTrack by mutableIntStateOf(0)
    var browseRoot by mutableStateOf<MusicRoot?>(null)
    var sleepTimerOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var trackMenu by mutableStateOf<MusicTrack?>(null)
    var info by mutableStateOf<MusicTrack?>(null)
    var deleteFile by mutableStateOf<MusicTrack?>(null)
    var moveFile by mutableStateOf<MusicTrack?>(null)
    var organizeOpen by mutableStateOf(false)
    var modeOpen by mutableStateOf(false)
    var trackMenuFromPlayer by mutableStateOf(false)
    fun openTrackMenu(track: MusicTrack, fromPlayer: Boolean = false) { trackMenuFromPlayer = fromPlayer; trackMenu = track }
}

@Composable
fun MusicNowPlaying(state: MusicPlaybackState, playback: MusicPlayback, favoriteIds: Set<String>, repository: MusicRepository,
                    modifier: Modifier = Modifier, onBack: () -> Unit, ui: MusicUiState, onDirectories: () -> Unit) {
    val track = state.current ?: return
    val context = LocalContext.current
    val supplementRevision by repository.supplementRevision.collectAsState()
    val reader = remember(track.id, supplementRevision, track.sizeBytes, track.modifiedAt) { MusicLyricsReader(context) }
    val lyrics by produceState<SongLyrics?>(null, reader) { value = null; value = reader.load(track) }
    DisposableEffect(reader) { onDispose { reader.cancel() } }
    val sleepTimer = remember { SleepTimer.shared(context) }
    val sleepState by sleepTimer.state.collectAsState()
    BackHandler(onBack = onBack)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (landscape) 18.dp else 24.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "收起音乐播放页") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { playback.stopAndDismiss(); onBack() }) { Icon(Icons.Default.PowerSettingsNew, "关闭音乐播放") }
            }
            if (landscape) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MusicArtworkPages(track, lyrics, state.positionMs, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    MusicPlayerTrackInfo(track, favoriteIds, repository, compact = true, sleepTimerActive = sleepState.active,
                        onMore = { ui.openTrackMenu(track, fromPlayer = true) })
                    MusicPlayerTransport(state, playback, ui, compact = true)
                }
            } else {
                MusicArtworkPages(track, lyrics, state.positionMs, Modifier.weight(1f).fillMaxWidth())
                MusicPlayerTrackInfo(track, favoriteIds, repository, compact = compact, sleepTimerActive = sleepState.active,
                    onMore = { ui.openTrackMenu(track, fromPlayer = true) })
                MusicPlayerTransport(state, playback, ui, compact = compact)
            }
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    TextButton(onClick = { playback.toggle() }) { Text("重试") }
                    IconButton(onClick = onDirectories) { Icon(Icons.Default.FolderOpen, "检查音乐目录") }
                }
            }
            if (!state.connected) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MusicArtworkPages(track: MusicTrack, lyrics: SongLyrics?, positionMs: Long, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(pageCount = { if (lyrics == null) 1 else 2 })
    LaunchedEffect(track.id) { pager.scrollToPage(0) }
    val scope = rememberCoroutineScope()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { if (it == 0) "artwork" else "lyrics" }) { page ->
            if (page == 0) BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp).semantics { contentDescription = "歌曲海报页" }, contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 360.dp).coerceAtLeast(0.dp)
                MusicArtwork(track, Modifier.size(side))
            } else lyrics?.let { MusicLyricsPanel(it, positionMs, Modifier.fillMaxSize().padding(horizontal = 4.dp)) }
        }
        if (lyrics != null) Row(Modifier.height(48.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("封面", "歌词").forEachIndexed { index, label ->
                val active = pager.currentPage == index
                Box(Modifier.size(48.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(index) } }), contentAlignment = Alignment.Center) {
                    Icon(if (index == 0) Icons.Outlined.Image else Icons.Outlined.Description, label, Modifier.size(22.dp),
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MusicPlayerTrackInfo(track: MusicTrack, favoriteIds: Set<String>, repository: MusicRepository, compact: Boolean,
                                 sleepTimerActive: Boolean, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(track.title, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(musicTrackSubtitle(track).ifBlank { track.path.substringAfterLast('/') }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { repository.toggleFavorite(track.id) }) { Icon(if (track.id in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (track.id in favoriteIds) "取消歌曲收藏" else "收藏歌曲", tint = MaterialTheme.colorScheme.primary) }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreHoriz, "歌曲更多操作", tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
    Text(musicAudioInfo(track), Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MusicPlayerTransport(state: MusicPlaybackState, playback: MusicPlayback, ui: MusicUiState, compact: Boolean) {
    val track = state.current ?: return
    var drag by remember(track.id) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
    Slider(value = drag ?: state.positionMs.coerceAtLeast(0).coerceAtMost(duration.coerceAtLeast(1)).toFloat(), onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { playback.seekTo(it.toLong()) }; drag = null },
        valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0, modifier = Modifier.fillMaxWidth())
    Text(musicPositionText(drag?.toLong() ?: state.positionMs, duration), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { ui.modeOpen = true }) { Icon(if (state.mode == MusicMode.SHUFFLE) Icons.Default.Shuffle else if (state.mode == MusicMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
            "播放模式：${musicModeLabel(state.mode)}", tint = MaterialTheme.colorScheme.primary) }
        IconButton(onClick = { playback.previous() }) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp)) }
        FilledIconButton(onClick = { playback.toggle() }, Modifier.size(if (compact) 58.dp else 68.dp), shape = CircleShape) {
            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "暂停音乐" else "继续音乐", Modifier.size(32.dp))
        }
        IconButton(onClick = { playback.next() }) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp)) }
        IconButton(onClick = { ui.queueOpen = true }) { Icon(Icons.Default.QueueMusic, "播放队列") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlaybackPanels(state: MusicPlaybackState, playback: MusicPlayback, repository: MusicRepository, favoriteIds: Set<String>, ui: MusicUiState, onAiSettings: () -> Unit = {}) {
    val organizeContext = LocalContext.current
    val organizePages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (ui.organizeOpen) organizePages.SaveableStateProvider("organize") {
        MusicOrganizePanel(onDismiss = { ui.organizeOpen = false }, onSettings = { onAiSettings(); ui.organizeOpen = false })
    }
    MusicFileDialogs(repository, playback, ui)
    if (ui.sleepTimerOpen) AlertDialog(onDismissRequest = { ui.sleepTimerOpen = false }, title = { Text("定时关闭") },
        text = { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { SleepTimerContent { ui.sleepTimerOpen = false } } }, confirmButton = {})
    val queueScroll = rememberLazyListState()
    LaunchedEffect(ui.queueOpen) {
        if (ui.queueOpen) state.queue.indexOfFirst { it.id == state.current?.id }.takeIf { it >= 0 }?.let { queueScroll.scrollToItem(it) }
    }
    if (ui.queueOpen) AppBottomSheet(onDismissRequest = { ui.queueOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 25.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列 · ${state.queue.size} 首", Modifier.weight(1f), fontSize = 20.sp)
                TextButton(onClick = { playback.clear(); ui.queueOpen = false }) { Text("清空") }
                IconButton(onClick = { ui.queueOpen = false }) { Icon(Icons.Default.Close, "关闭播放队列") }
            }
            Text(musicModeLabel(state.mode), Modifier.padding(vertical = 10.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MusicListScrollbar(queueScroll, Modifier.fillMaxWidth().heightIn(max = 470.dp)) {
            LazyColumn(Modifier.fillMaxWidth(), state = queueScroll) {
                if (state.queue.isEmpty()) item { Text("队列为空", Modifier.padding(vertical = 25.dp), fontSize = 13.sp) }
                items(state.queue, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().semantics { selected = state.current?.id == track.id }.background(if (state.current?.id == track.id) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else androidx.compose.ui.graphics.Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { playback.playQueued(track.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MusicArtwork(track, Modifier.size(38.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(track.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (state.current?.id == track.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(track.artist.ifBlank { track.format }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.current?.id == track.id) Icon(Icons.Default.GraphicEq, "当前歌曲", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { playback.remove(track.id) }) { Icon(Icons.Default.Close, "从队列移除 ${track.title}", Modifier.size(18.dp)) }
                    }
                }
            }
            }
        }
    }
    ui.trackMenu?.let { track ->
        AppBottomSheet(onDismissRequest = { ui.trackMenu = null }) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(track.title, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!ui.trackMenuFromPlayer) {
                ChannelRow("下一首播放", icon = Icons.Default.SkipNext, onClick = { playback.enqueue(track, next = true); ui.trackMenu = null })
                ChannelRow("加入播放队列", icon = Icons.Default.QueueMusic, onClick = { playback.enqueue(track); ui.trackMenu = null })
                ChannelRow(if (track.id in favoriteIds) "取消收藏" else "加入我的收藏", icon = Icons.Default.FavoriteBorder, onClick = { repository.toggleFavorite(track.id); ui.trackMenu = null })
                } else {
                    val menuContext = LocalContext.current
                    val timer = remember { SleepTimer.shared(menuContext) }
                    val timerState by timer.state.collectAsState()
                    ChannelRow(if (timerState.active) "定时关闭 · ${sleepTimerText(timerState.remainingMs)}" else "定时关闭", icon = Icons.Default.Timer,
                        onClick = { ui.trackMenu = null; ui.sleepTimerOpen = true })
                }
                ChannelRow("整理资料", icon = Icons.Default.AutoFixHigh, beta = true, onClick = { com.megatron.player.music.metadata.MusicOrganizeEngine.shared(organizeContext).prepare(listOf(track)); ui.trackMenu = null; ui.organizeOpen = true })
                ChannelRow("移动到其他目录", icon = Icons.Default.DriveFileMove, onClick = { ui.trackMenu = null; ui.moveFile = track })
                ChannelRow("删除文件", icon = Icons.Default.DeleteOutline, onClick = { ui.trackMenu = null; ui.deleteFile = track })
                ChannelRow("文件信息", icon = Icons.Default.Info, onClick = { ui.trackMenu = null; ui.info = track })
            }
        }
    }
    if (ui.modeOpen) AppBottomSheet(onDismissRequest = { ui.modeOpen = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("播放模式", fontSize = 20.sp)
            MusicMode.entries.forEach { mode -> ChannelRow(musicModeLabel(mode), onClick = { playback.setMode(mode); ui.modeOpen = false },
                trailing = { if (state.mode == mode) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }) }
        }
    }
    ui.info?.let { track ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { ui.info = null }, title = { Text("文件信息") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(track.path.substringAfterLast('/'), fontSize = 14.sp)
                Text(listOf("位置：${track.path}", "格式：${musicAudioInfo(track)}", "时长：${if (track.durationMs > 0) musicTime(track.durationMs) else "尚未读取"}",
                    "大小：${if (track.sizeBytes > 0) "%.1f MB".format(track.sizeBytes / 1048576.0) else "未知"}").joinToString("\n\n"), fontSize = 12.sp)
                if (state.current?.id == track.id && state.outputInfo.isNotBlank()) {
                    HorizontalDivider()
                    Text("当前播放输出", fontSize = 13.sp)
                    Text(state.outputInfo, fontSize = 11.sp, lineHeight = 18.sp)
                    TextButton(onClick = { clipboard.setText(AnnotatedString("Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\nNASPlayer ${BuildConfig.VERSION_NAME} / Media3 1.9.1\n${state.outputInfo}")) }) { Text("复制播放信息") }
                }
            }
        }, confirmButton = { TextButton(onClick = { ui.info = null }) { Text("关闭") } })
    }
}
