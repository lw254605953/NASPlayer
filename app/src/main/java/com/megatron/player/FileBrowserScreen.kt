package com.megatron.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.cast.*
import com.megatron.player.source.*
import com.megatron.player.music.*
import com.megatron.player.plex.PlexBrowseSession
import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.PlexPart
import com.megatron.player.plex.PlexServerConfig
import com.megatron.player.plex.PlexWebDavBindingStore
import com.megatron.player.plex.PlexFavorite
import com.megatron.player.plex.PlexFavoritesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    history: List<RecentVideo>,
    plexSession: PlexBrowseSession,
    plexPageMemory: PlexLibraryPageMemory,
    onPlay: (Uri, String) -> Unit,
    onPlayPlex: (PlexServerConfig, PlexMovie, PlexPart) -> Unit,
    onOpenLocal: () -> Unit,
    onChooseDirectory: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
    onOpenCast: (RecentVideo) -> Unit,
    onDeleteRecent: (RecentVideo) -> Unit,
    onPlayTimePoint: (RecentVideo) -> Unit,
    onClearHistory: () -> Unit,
    sourceRevision: Int,
    musicRepository: MusicRepository,
    musicPlayback: MusicPlayback,
    musicUi: MusicUiState,
) {
    val musicState by musicPlayback.state.collectAsState()
    val musicTracks by musicRepository.tracks.collectAsState()
    val musicFavoriteIds by musicRepository.favoriteIds.collectAsState()
    val musicFileRevision by musicRepository.fileRevision.collectAsState()
    val musicScreenState = rememberSaveableStateHolder()
    var musicPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var musicDirectoriesOpen by remember { mutableStateOf(false) }
    var directoryAfterSource by remember { mutableStateOf<String?>(null) }
    var videoDirectories by remember { mutableStateOf<MusicVideoCategory?>(null) }
    val context = LocalContext.current
    val musicVideos = remember { MusicVideoRepository(context) }
    val caster = remember { CastController.shared(context) }
    val cast by caster.state.collectAsState()
    val store = remember { SourceStore(context) }
    val titleFavoritesStore = remember { PlexFavoritesStore(context) }
    val videoCollections = remember { VideoCollections(context) }
    var timePoints by remember { mutableStateOf<List<VideoTimePoint>>(emptyList()) }
    var videoFiles by remember { mutableStateOf<List<VideoFileFavorite>>(emptyList()) }
    var titleFavorites by remember { mutableStateOf<List<PlexFavorite>>(emptyList()) }
    var favoriteFailure by remember { mutableStateOf<String?>(null) }
    val bindingStore = remember { PlexWebDavBindingStore(context) }
    val homePreferences = remember { HomeDisplayPreferences(context) }
    var showHomeContinue by remember { mutableStateOf(homePreferences.showContinueWatching()) }
    var preferencesDialog by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var directoryRefresh by remember { mutableIntStateOf(0) }
    val pageStates = rememberSaveableStateHolder()
    var plexTarget by rememberSaveable { mutableStateOf<PlexDetailTarget?>(null) }
    var backStack by rememberSaveable { mutableStateOf<List<BrowserReturn>>(emptyList()) }
    var tabLocations by rememberSaveable { mutableStateOf(hashMapOf<String, BrowserLocation>()) }
    var navigationFailure by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<MediaSourceConfig>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<SavedLocation>>(emptyList()) }
    var tab by rememberSaveable { mutableStateOf("首页") }
    var settingsPage by rememberSaveable { mutableStateOf("main") }
    var homePage by rememberSaveable { mutableStateOf("main") }
    val sourceFailures = remember { mutableStateMapOf<String, String>() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var paths by rememberSaveable { mutableStateOf(listOf<String>()) }
    var labels by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortTime by rememberSaveable { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var sourceMenu by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var removing by remember { mutableStateOf<MediaSourceConfig?>(null) }
    var entryMenu by remember { mutableStateOf<BrowserEntry?>(null) }
    var fileInfo by remember { mutableStateOf<BrowserEntry?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(HttpAddressValue()) }
    var clearDialog by remember { mutableStateOf(false) }
    val source = sources.firstOrNull { it.id == selectedId }
    val currentPath = paths.lastOrNull() ?: ""
    val bindingResult = remember(plexSession.config?.id, revision, sourceRevision) {
        runCatching { plexSession.config?.id?.let(bindingStore::read) }
    }

    fun location() = BrowserLocation(tab, homePage, settingsPage, selectedId, paths, labels, plexTarget, musicPlayerOpen, musicUi.organizeOpen, query, sortTime)
    fun restore(next: BrowserLocation) {
        tab = next.tab; homePage = next.homePage; settingsPage = next.settingsPage
        selectedId = next.sourceId?.takeIf { id -> sources.any { it.id == id } }
        paths = if (selectedId != null) next.paths else emptyList()
        labels = if (selectedId != null) next.labels else emptyList()
        plexTarget = next.detail
        musicPlayerOpen = next.musicPlayer; musicUi.organizeOpen = next.organize
        query = next.query; sortTime = next.sortTime
    }
    fun visit(next: BrowserLocation) {
        backStack = pushBrowserReturn(backStack, location(), next)
        restore(next)
    }
    fun canReturn() = browserReturn(backStack, location()) != null
    fun back(fallback: () -> Unit) {
        val previous = browserReturn(backStack, location())
        if (previous != null) { backStack = backStack.dropLast(1); restore(previous.origin) } else fallback()
    }
    fun settings(page: String = "main") = visit(location().copy(tab = "设置", settingsPage = page, detail = null, musicPlayer = false, organize = false))
    fun closeSettings() = back { settingsPage = browserParentSettings(settingsPage) }
    fun openDetails(target: PlexDetailTarget) {
        if (plexSession.credentials?.config?.id != target.serverId) { navigationFailure = "对应的 Plex 片源尚未连接"; return }
        visit(location().copy(detail = target, musicPlayer = false, organize = false))
    }
    fun switchTab(name: String) {
        if (tab == name && plexTarget == null && !musicPlayerOpen) return
        tabLocations = HashMap(tabLocations).apply { put(tab, location().copy(detail = null, musicPlayer = false, organize = false)) }
        backStack = emptyList()
        restore(tabLocations[name] ?: BrowserLocation(tab = name))
    }

    fun refreshDirectory() {
        runCatching { source?.takeIf { it.kind == SourceKind.WEBDAV }?.let { s ->
            store.credentials(s.id)?.let { DirectoryBrowserCache.invalidate(it, currentPath) }
        } }
        directoryRefresh++
    }
    fun choose(config: MediaSourceConfig, path: String? = null, title: String? = null) {
        val root = if (config.kind == SourceKind.LOCAL) LocalFiles.root(config) else ""
        val nextPaths = if (path != null && path != root) listOf(root, path) else listOf(root)
        val nextLabels = if (nextPaths.size == 2) listOf(config.name, title ?: "目录") else listOf(config.name)
        visit(location().copy(tab = "首页", homePage = "main", sourceId = config.id, paths = nextPaths, labels = nextLabels, query = "", detail = null, musicPlayer = false, organize = false))
        entries = emptyList(); failure = null
    }
    fun up() {
        if (canReturn()) back { selectedId = null }
        else if (paths.size > 1) { paths = paths.dropLast(1); labels = labels.dropLast(1); query = "" }
        else selectedId = null
    }
    fun toggleFavorite(item: BrowserEntry) {
        source?.let { store.toggleFavorite(SavedLocation(it.id, item.path, item.name, item.isDirectory, item.documentUri)); revision++ }
    }
    fun openEntry(item: BrowserEntry) {
        val config = source ?: return
        if (item.isDirectory) { visit(location().copy(paths = paths + item.path, labels = labels + item.name, query = "")); entries = emptyList() }
        else if (!isVideoFile(item.name)) fileInfo = item
        else onPlay(if (config.kind == SourceKind.WEBDAV) SourceUris.build(config.id, item.path) else Uri.parse(item.documentUri), item.name)
    }
    fun openFavorite(item: SavedLocation) {
        val config = sources.firstOrNull { it.id == item.sourceId } ?: run { favoriteFailure = "片源已移除，可以删除这条收藏。"; return }
        if (item.isDirectory) choose(config, item.path, item.name)
        else onPlay(if (config.kind == SourceKind.WEBDAV) SourceUris.build(config.id, item.path) else Uri.parse(item.documentUri), item.name)
    }

    LaunchedEffect(revision, sourceRevision) {
        try {
            sources = store.list(); favorites = store.favorites()
            if (selectedId != null && sources.none { it.id == selectedId }) selectedId = null
        }
        catch (error: Exception) { failure = "连接配置无法读取，请重新添加片源。" }
    }
    LaunchedEffect(tab, homePage, plexSession.credentials?.config?.id) {
        if (tab == "文件") tab = "首页"
        if (tab == "首页" && homePage == "continue" && plexSession.credentials != null && !plexSession.continueLoading) plexSession.refreshContinueWatching(force = false)
    }
    LaunchedEffect(tab, settingsPage, revision) {
        if (tab == "收藏") { tab = "设置"; settingsPage = "favorites" }
        if (tab == "设置" && settingsPage == "favorites") {
            try { titleFavorites = titleFavoritesStore.list(); timePoints = videoCollections.points(); videoFiles = videoCollections.files(); favoriteFailure = null }
            catch (_: Exception) { favoriteFailure = "影片收藏无法读取，请重试。" }
        }
    }
    LaunchedEffect(source?.id, source?.address, source?.username, currentPath, directoryRefresh, sourceRevision, musicFileRevision) {
        val config = source ?: return@LaunchedEffect
        loading = true; failure = null; entries = emptyList()
        try {
            entries = withContext(Dispatchers.IO) {
                when (config.kind) {
                    SourceKind.LOCAL -> LocalFiles.list(context, config, currentPath)
                    SourceKind.WEBDAV -> DirectoryBrowserCache.list(store.credentials(config.id) ?: error("片源已移除"), currentPath)
                }
            }
            sourceFailures.remove(config.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = sourceErrorMessage(error); sourceFailures[config.id] = failure!! }
        finally { loading = false }
    }
    LaunchedEffect(musicState.current?.id, musicState.dismissed) {
        if (musicState.current == null || musicState.dismissed) {
            musicPlayerOpen = false; musicUi.queueOpen = false; musicUi.trackMenu = null; musicUi.info = null; musicUi.modeOpen = false
        }
    }
    BackHandler(enabled = plexTarget == null && !musicPlayerOpen && canReturn()) { back {} }
    BackHandler(enabled = plexTarget == null && tab == "设置" && (settingsPage != "main" || canReturn())) { closeSettings() }
    BackHandler(enabled = plexTarget == null && tab == "首页" && selectedId != null) { up() }
    BackHandler(enabled = plexTarget == null && tab == "首页" && selectedId == null && (homePage != "main" || canReturn())) { back { homePage = "main" } }
    Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding(), containerColor = PlayerTheme.background,
        bottomBar = {
            if (!musicPlayerOpen) Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                CastMiniPlayer(caster, onOpen = { onOpenCast(RecentVideo(cast.uri, cast.title, cast.positionMs, cast.durationMs)) })
                if (!musicState.dismissed) MusicMiniPlayer(musicState, musicPlayback, onOpen = { musicPlayerOpen = true }, onQueue = { musicUi.queueOpen = true })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
                Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    listOf("首页" to Icons.Default.FolderOpen, "媒体库" to Icons.Default.VideoLibrary, "音乐" to Icons.Default.LibraryMusic, "设置" to Icons.Default.Tune).forEach { (name, image) ->
                        val active = tab == name
                        Column(Modifier.weight(1f).fillMaxHeight().selectable(selected = active, role = Role.Tab,
                            onClick = { switchTab(name) }), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(20.dp).height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                            Spacer(Modifier.height(10.dp))
                            Icon(image, null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(name, fontSize = 10.sp, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }) { padding ->
        pageStates.SaveableStateProvider(location().key) {
        if (plexTarget != null) PlexLibraryScreen(Modifier.padding(padding), plexSession, onOpenSettings = { settings() }, openTarget = plexTarget,
            onExternalDetailBack = { back { plexTarget = null } }, externalBackLabel = backStack.lastOrNull()?.origin?.backLabel ?: "返回",
            onPlay = onPlayPlex)
        else if (musicPlayerOpen && musicState.current != null && !musicState.dismissed) MusicNowPlaying(musicState, musicPlayback, musicFavoriteIds, musicRepository, Modifier.padding(padding),
            onBack = { musicPlayerOpen = false }, ui = musicUi, onDirectories = { musicDirectoriesOpen = true })
        else if (tab == "音乐") musicScreenState.SaveableStateProvider("music") {
            MusicScreen(musicRepository, musicPlayback, musicState, sources, revision + sourceRevision, Modifier.padding(padding),
                onDirectories = { musicDirectoriesOpen = true }, onTrackMenu = { musicUi.openTrackMenu(it) },
                videos = musicVideos, ui = musicUi, onVideoDirectories = { videoDirectories = it }, onPlayVideo = onPlay)
        }
        else if (tab == "媒体库") PlexLibraryScreen(Modifier.padding(padding), plexSession, onOpenSettings = { settings() },
            pageMemory = plexPageMemory, rootBackLabel = backStack.lastOrNull()?.origin?.backLabel ?: "返回", onRootBack = if (canReturn()) ({ back { tab = "首页" } }) else null) { config, movie, part -> onPlayPlex(config, movie, part) }
        else if (tab == "首页" && selectedId == null && homePage == "continue") ContinueWatchingScreen(plexSession, history, Modifier.padding(padding), onPlayPlex, onPlayRecent,
            onOpenDetails = ::openDetails, onOpenSettings = { settings() }, onBack = { back { homePage = "main" } })
        else if (tab == "首页" && selectedId == null) AppHomeScreen(sources, plexSession, bindingResult.getOrNull()?.sourceId,
            bindingResult.exceptionOrNull()?.message, history, Modifier.padding(padding), showAllSources = homePage == "sources", showContinueWatching = showHomeContinue,
            onShowAllSources = { visit(location().copy(homePage = "sources")) }, onAddSource = { addMenu = true }, onOpenSource = { choose(it) },
            onOpenLibrary = { visit(location().copy(tab = "媒体库", detail = null)) }, onOpenSettings = { settings() }, onShowContinue = { visit(location().copy(homePage = "continue")) },
            onOpenDetails = ::openDetails,
            onPlayPlex = onPlayPlex, onPlayRecent = onPlayRecent,
            onBack = { back { homePage = "main" } }, sourceFailures = sourceFailures.toMap())
        else if (tab == "设置" && settingsPage == "favorites") MyFavoritesScreen(titleFavorites, favorites, musicTracks.filter { it.id in musicFavoriteIds },
            sources, plexSession, musicState, Modifier.padding(padding), favoriteFailure,
            onBack = ::closeSettings, onRetry = { revision++ },
            onOpenTitle = { item ->
                val config = plexSession.config
                if (config != null && item.belongsTo(config)) { openDetails(PlexDetailTarget(config.id, item.movie.ratingKey)) }
                else favoriteFailure = "这条收藏的 Plex 片源尚未连接，请在设置中连接对应片源。"
            }, onRemoveTitle = { item ->
                try { titleFavoritesStore.remove(item); titleFavorites = titleFavoritesStore.list(); favoriteFailure = null }
                catch (_: Exception) { favoriteFailure = "取消收藏失败，请重试。" }
            }, onOpenFile = ::openFavorite, onRemoveFile = { store.toggleFavorite(it); revision++ },
            onPlayMusic = { musicPlayback.play(musicTracks.filter { it.id in musicFavoriteIds }, it.id) },
            onMusicMenu = { musicUi.openTrackMenu(it) }, onLibrary = { visit(location().copy(tab = "媒体库", detail = null)) },
            timePoints = timePoints, videoFiles = videoFiles,
            onOpenPoint = { point ->
                val unavailable = videoCollections.unavailable(point.video, point.plexMachine)
                if (unavailable == null) onPlayTimePoint(point.video) else favoriteFailure = unavailable
            }, onRemovePoint = { point ->
                try { videoCollections.remove(point); timePoints = videoCollections.points(); favoriteFailure = null }
                catch (_: Exception) { favoriteFailure = "删除时间点失败，请重试。" }
            }, onRenamePoint = { point, name ->
                try { videoCollections.rename(point, name); timePoints = videoCollections.points(); favoriteFailure = null }
                catch (_: Exception) { favoriteFailure = "重命名时间点失败，请重试。" }
            }, onOpenVideo = { file ->
                val unavailable = videoCollections.unavailable(file.video, file.plexMachine)
                if (unavailable == null) onPlayRecent(file.video) else favoriteFailure = unavailable
            }, onRemoveVideo = { file ->
                try { videoCollections.remove(file); videoFiles = videoCollections.files(); favoriteFailure = null }
                catch (_: Exception) { favoriteFailure = "取消收藏失败，请重试。" }
            })
        else if (tab == "设置" && settingsPage == "progress") ProgressSyncScreen(Modifier.padding(padding), onBack = ::closeSettings)
        else if (tab == "设置" && settingsPage == "history") WatchHistoryScreen(history, Modifier.padding(padding), onPlayRecent, onDeleteRecent, onBack = ::closeSettings)
        else if (tab == "设置" && settingsPage == "ai") AiSettingsScreen(Modifier.padding(padding), onBack = ::closeSettings, backLabel = backStack.lastOrNull()?.origin?.backLabel ?: "返回设置")
        else if (tab == "设置" && settingsPage == "backup") BackupScreen(sources, Modifier.padding(padding),
            onBack = ::closeSettings, onConnect = { editing = null; editor = true })
        else if (tab == "设置") AppSettingsScreen(plexSession, sources, bindingResult.getOrNull()?.sourceId,
            bindingResult.exceptionOrNull()?.message, showHomeContinue, history.isNotEmpty(), settingsPage, Modifier.padding(padding),
            onPageChange = { if (it == browserParentSettings(settingsPage)) closeSettings() else settings(it) }, onShowContinueChange = { showHomeContinue = it; homePreferences.setShowContinueWatching(it) },
            onOpenSource = { config -> if (config.kind == SourceKind.WEBDAV) { editing = config; editor = true } else choose(config) },
            onSourceMenu = { sourceMenu = it }, onAddSource = { addMenu = true }, onSourcesChanged = { revision++ },
            onPreferences = { preferencesDialog = true }, onHistory = { settings("history") }, onProgressSync = { settings("progress") },
            onClearHistory = { clearDialog = true }, onFavorites = { settings("favorites") },
            onMusicDirectories = { musicDirectoriesOpen = true }, onBackup = { settings("backup") }, onRootBack = if (canReturn()) ::closeSettings else null)
        else Column(Modifier.padding(padding).fillMaxSize()) {
            if (source != null && tab == "首页") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::up) { Icon(Icons.Default.ArrowBack, if (canReturn()) backStack.lastOrNull()?.origin?.backLabel ?: "返回" else "上一级") }
                    Text(source.name, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { refreshDirectory() }) { Icon(Icons.Default.Refresh, "刷新目录") }
                    IconButton(onClick = { sourceMenu = source }) { Icon(Icons.Default.MoreVert, "片源操作") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(labels.joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${entries.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { toggleFavorite(BrowserEntry(labels.lastOrNull() ?: source.name, currentPath, true)) }) {
                        Icon(if (favorites.any { it.sourceId == source.id && it.path == currentPath }) Icons.Default.Star else Icons.Default.StarBorder, "收藏当前目录", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { sortTime = !sortTime }) { Icon(Icons.Default.Sort, if (sortTime) "按名称排序" else "按修改时间排序") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    placeholder = { Text("搜索当前目录", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface))
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    failure != null -> BrowserEmpty(Icons.Default.CloudOff, failure!!, "重试", { refreshDirectory() }, "编辑连接", { editing = source; editor = true })
                    else -> {
                        val filtered = entries.filter { it.name.contains(query, ignoreCase = true) }.sortedWith(
                            compareBy<BrowserEntry> { !it.isDirectory }.thenComparator { a, b -> if (sortTime) b.modifiedAt.compareTo(a.modifiedAt) else a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT)) })
                        if (filtered.isEmpty()) BrowserEmpty(Icons.Default.FolderOpen, if (query.isNotBlank()) "当前目录没有匹配文件" else "这个目录是空的")
                        else LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                            items(filtered, key = { it.path }) { item ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f).clickable { openEntry(item) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null,
                                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp), tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(if (item.isDirectory) "文件夹" else entryDescription(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = { entryMenu = item }) { Icon(Icons.Default.MoreVert, "${item.name}更多操作") }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f))
                            }
                        }
                    }
                }
            }
        }
        }
    }
    MusicPlaybackPanels(musicState, musicPlayback, musicRepository, musicFavoriteIds, musicUi, onAiSettings = { settings("ai") })
    if (musicDirectoriesOpen) MusicDirectoryPicker(sources, musicRepository,
        onOpenRoot = { musicUi.browseRoot = it; musicDirectoriesOpen = false; visit(location().copy(tab = "音乐", musicPlayer = false, detail = null)) },
        onDismiss = { musicDirectoriesOpen = false }, onAddSource = { musicDirectoriesOpen = false; directoryAfterSource = "音乐"; editing = null; editor = true })
    val videoRoots by musicVideos.roots.collectAsState()
    videoDirectories?.let { category ->
        MediaDirectoryPicker(sources, videoRoots[category].orEmpty(), category.label, musicVideos::listDirectory,
            { musicVideos.save(category, it) }, onDismiss = { videoDirectories = null }, onAddSource = {
                directoryAfterSource = category.label; videoDirectories = null; editing = null; editor = true
            })
    }
    navigationFailure?.let { issue -> AlertDialog(onDismissRequest = { navigationFailure = null }, title = { Text("无法打开") }, text = { Text(issue) }, confirmButton = { TextButton(onClick = { navigationFailure = null }) { Text("关闭") } }) }
    if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("添加") }, text = {
        Column {
            SimpleFileRow("WebDAV", "", Icons.Default.Cloud) { addMenu = false; editing = null; editor = true }
            SimpleFileRow("Plex", "", Icons.Default.VideoLibrary) { addMenu = false; settings("plex") }
            SimpleFileRow("本机目录", "", Icons.Default.FolderOpen) { addMenu = false; onChooseDirectory() }
            SimpleFileRow("本地视频", "", Icons.Default.Movie) { addMenu = false; onOpenLocal() }
            SimpleFileRow("播放视频链接", "", Icons.Default.Link) { addMenu = false; urlDialog = true }
        }
    }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("关闭") } })
    if (editor) SourceEditorDialog(editing, store, { editor = false; directoryAfterSource = null }, { config ->
        val wasEditing = editing != null
        revision++; editor = false
        if (directoryAfterSource != null) {
            if (directoryAfterSource == "音乐") musicDirectoriesOpen = true
            else videoDirectories = MusicVideoCategory.fromLabel(directoryAfterSource!!)
            directoryAfterSource = null
        }
        else if (!wasEditing) choose(config)
        else if (selectedId == config.id && labels.isNotEmpty()) labels = listOf(config.name) + labels.drop(1)
    })
    sourceMenu?.let { config -> AlertDialog(onDismissRequest = { sourceMenu = null }, title = { Text(config.name) }, text = {
        Column {
            if (config.kind == SourceKind.WEBDAV) TextButton(onClick = { sourceMenu = null; editing = config; editor = true }) { Text("编辑连接") }
            TextButton(onClick = { sourceMenu = null; removing = config }) { Text("移除片源") }
        }
    }, confirmButton = { TextButton(onClick = { sourceMenu = null }) { Text("关闭") } }) }
    removing?.let { config -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 ${config.name}？") }, text = { Text("同时移除该连接的收藏。") },
        confirmButton = { TextButton(onClick = { if (musicState.current?.sourceId == config.id) musicPlayback.pauseForVideo(); store.remove(config.id); if (selectedId == config.id) selectedId = null; removing = null; revision++ }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
    entryMenu?.let { item -> AlertDialog(onDismissRequest = { entryMenu = null }, title = { Text(item.name, maxLines = 2) }, text = {
        Column {
            TextButton(onClick = { entryMenu = null; openEntry(item) }) { Text(if (item.isDirectory) "打开目录" else "播放文件") }
            val saved = favorites.any { it.sourceId == source?.id && it.path == item.path }
            TextButton(onClick = { toggleFavorite(item); entryMenu = null }) { Text(if (saved) "取消收藏" else "加入收藏") }
            TextButton(onClick = { entryMenu = null; fileInfo = item }) { Text("文件信息") }
        }
    }, confirmButton = { TextButton(onClick = { entryMenu = null }) { Text("关闭") } }) }
    fileInfo?.let { item -> AlertDialog(onDismissRequest = { fileInfo = null }, title = { Text("文件信息") }, text = { Text("${item.name}\n\n${if (item.isDirectory) "文件夹" else entryDescription(item)}\n来源：${source?.name.orEmpty()}\n位置：${item.path}") }, confirmButton = { TextButton(onClick = { fileInfo = null }) { Text("关闭") } }) }
    if (urlDialog) AlertDialog(onDismissRequest = { urlDialog = false }, title = { Text("播放视频直链") }, text = {
        HttpAddressField(url, { url = it }, label = "视频服务器与路径", placeholder = "nas.example.com/video.mkv")
    }, confirmButton = { TextButton(onClick = { onOpenUrl(url.fullUrl()); urlDialog = false }, enabled = url.address.isNotBlank()) { Text("播放") } }, dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("取消") } })
    if (preferencesDialog) AlertDialog(onDismissRequest = { preferencesDialog = false },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { PlaybackPreferencesPanel() } },
        confirmButton = { TextButton(onClick = { preferencesDialog = false }) { Text("完成") } })
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清除观看记录？") },
        confirmButton = { TextButton(onClick = { onClearHistory(); clearDialog = false }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
}

@Composable
private fun SimpleFileRow(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Icon(icon, null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BrowserEmpty(icon: ImageVector, text: String, action: String? = null, onAction: () -> Unit = {}, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium)
        action?.let { Button(onClick = onAction) { Text(it) } }
        secondary?.let { TextButton(onClick = onSecondary) { Text(it) } }
    }
}

private fun entryDescription(item: BrowserEntry): String = buildString {
    val size = when {
        item.size >= 1024L * 1024 * 1024 -> "%.1f GB".format(item.size.toDouble() / (1024L * 1024 * 1024))
        item.size >= 1024L * 1024 -> "%.1f MB".format(item.size.toDouble() / (1024L * 1024))
        item.size > 0 -> "${item.size / 1024} KB"
        else -> "大小未知"
    }
    append(size)
    if (item.modifiedAt > 0) append("  ·  ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.modifiedAt))}")
}

internal fun isVideoFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
    setOf("mp4", "mkv", "m4v", "f4v", "mov", "webm", "avi", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "flv", "wmv", "3gp")
