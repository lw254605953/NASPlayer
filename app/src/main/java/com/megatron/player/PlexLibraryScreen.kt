package com.megatron.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlexLibraryScreen(modifier: Modifier = Modifier, session: PlexBrowseSession, onOpenSettings: () -> Unit, openTarget: PlexDetailTarget? = null, onTargetConsumed: () -> Unit = {},
    entryCategory: String? = null, onEntryConsumed: () -> Unit = {}, onExternalDetailBack: (() -> Unit)? = null, externalBackLabel: String = "返回", onRootBack: (() -> Unit)? = null, rootBackLabel: String = "返回", pageMemory: PlexLibraryPageMemory? = null, onPlay: (PlexServerConfig, PlexMovie, PlexPart) -> Unit) {
    val context = LocalContext.current
    val client = session.client
    val credentials = session.credentials
    // Connection identity excludes the editable display name and never leaves memory.
    val connectionIdentity = credentials?.let { listOf(it.config.id, it.config.machineIdentifier, it.config.address, it.token) }
    val libraries = session.libraries
    val loading = session.loading
    val failure = session.failure
    val preferences = remember { PlexLibraryPreferences(context) }
    val scope = rememberCoroutineScope()
    val pageState = remember(connectionIdentity, pageMemory) { pageMemory?.forConnection(connectionIdentity) ?: PlexLibraryPageState() }
    val gridState = pageState.grid
    val listState = pageState.list
    var libraryRefresh by pageState::libraryRefresh
    var detailRefresh by remember { mutableIntStateOf(0) }
    var forceLibraryRefresh by pageState::forceLibraryRefresh
    var category by rememberSaveable { mutableStateOf(preferences.category()) }
    var browsingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var movieKey by rememberSaveable { mutableStateOf(openTarget?.ratingKey) }
    var query by rememberSaveable(libraryKey) { mutableStateOf("") }
    var globalSearch by rememberSaveable { mutableStateOf(false) }
    var globalQuery by rememberSaveable { mutableStateOf("") }
    val globalSearchState = pageState.globalSearch
    val globalListState = pageState.globalList
    var filters by rememberSaveable(connectionIdentity, libraryKey) { mutableStateOf(PlexLibraryFilter()) }
    var filterDialog by remember { mutableStateOf(false) }
    var offset by pageState::offset
    var movies by pageState::movies
    var total by pageState::total
    var nextPageOffset by pageState::nextPageOffset
    var lastAppliedOffset by pageState::lastAppliedOffset
    var detail by pageState::detail
    var displayedPageKey by pageState::displayedPageKey
    var requestedSeason by rememberSaveable { mutableStateOf(openTarget?.seasonIndex) }
    var watchBusyKey by remember(connectionIdentity) { mutableStateOf<String?>(null) }
    var watchFailure by remember(connectionIdentity) { mutableStateOf<String?>(null) }
    var libraryLoading by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }
    var libraryFailure by remember { mutableStateOf<String?>(null) }
    var detailFailure by remember { mutableStateOf<String?>(null) }
    val serverId = credentials?.config?.id
    val selectedLibrary = libraries.firstOrNull { it.key == libraryKey }
    val seriesLibrary = selectedLibrary?.type == "show"
    val seriesDetail = detail?.type == "show"
    val availableSorts = if (seriesLibrary) PlexSort.entries.filterNot { it == PlexSort.DURATION } else PlexSort.entries
    var options by remember(serverId, libraryKey, seriesLibrary) {
        val saved = if (serverId != null && libraryKey != null) preferences.options(serverId, libraryKey!!) else PlexLibraryOptions()
        mutableStateOf(if (saved.sort in availableSorts) saved else saved.copy(sort = PlexSort.RELEASED, descending = true))
    }
    var searchHistory by remember(serverId, libraryKey) { mutableStateOf(if (serverId != null && libraryKey != null) preferences.history(serverId, libraryKey!!) else emptyList()) }

    fun recordSearch(value: String = query) {
        val id = serverId ?: return
        val key = libraryKey ?: return
        preferences.recordSearch(id, key, value)
        searchHistory = preferences.history(id, key)
    }
    fun updateOptions(next: PlexLibraryOptions) {
        val id = serverId ?: return
        val key = libraryKey ?: return
        if (next.sort != options.sort || next.descending != options.descending) offset = 0
        if (next.view != options.view) {
            val anchor = if (options.view == PlexViewMode.LIST) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
            scope.launch { if (next.view == PlexViewMode.LIST) listState.scrollToItem(anchor) else gridState.scrollToItem(anchor) }
        }
        options = next
        preferences.saveOptions(id, key, next)
    }
    LaunchedEffect(serverId, session.revision, loading) {
        if (serverId != null && browsingServerId != null && browsingServerId != serverId) { libraryKey = null; movieKey = null; query = ""; globalSearch = false; globalQuery = ""; offset = 0 }
        if (serverId != null) browsingServerId = serverId
        if (!loading && credentials != null && libraryKey != null && libraries.none { it.key == libraryKey }) { libraryKey = null; movieKey = null }
    }

    LaunchedEffect(openTarget?.sequence, serverId) {
        val target = openTarget ?: return@LaunchedEffect
        if (target.serverId == serverId) {
            globalSearch = false; libraryKey = null; detail = null; movieKey = target.ratingKey; requestedSeason = target.seasonIndex
            onTargetConsumed()
        }
    }

    LaunchedEffect(entryCategory) {
        val target = entryCategory ?: return@LaunchedEffect
        category = if (target == "show") "show" else "movie"
        preferences.saveCategory(category)
        globalSearch = false; libraryKey = null; movieKey = null; detail = null; requestedSeason = null; query = ""; offset = 0
        onEntryConsumed()
    }

    LaunchedEffect(connectionIdentity, session.revision, loading, libraryKey, seriesLibrary, query, offset, libraryRefresh, options.sort, options.descending, filters) {
        credentials ?: return@LaunchedEffect
        val key = libraryKey ?: return@LaunchedEffect
        val library = libraries.firstOrNull { it.key == key } ?: return@LaunchedEffect
        val requestKey = "$key|${query.trim()}|$offset|${options.sort}|${options.descending}|$filters|${session.revision}|$libraryRefresh"
        if (pageState.completedRequest == requestKey) return@LaunchedEffect
        val force = forceLibraryRefresh && offset == 0
        forceLibraryRefresh = false
        libraryLoading = true; libraryFailure = null
        try {
            val pageKey = "$key|${query.trim()}|${options.sort}|${options.descending}|$filters"
            val resetPosition = offset == 0 && (displayedPageKey != pageKey || force)
            if (offset == 0) {
                nextPageOffset = null; lastAppliedOffset = -1
                if (displayedPageKey != pageKey) { movies = emptyList(); total = 0 }
                displayedPageKey = pageKey
                if (!filters.active) session.cachedPage(library, query.trim(), 0, options.sort, options.descending)?.let {
                    movies = it.movies; total = it.total
                }
            }
            if (query.isNotEmpty() && offset == 0) delay(350)
            val page = session.loadPage(library, query.trim(), offset, options.sort, options.descending, force, filters)
            val next = nextPlexPageOffset(page, offset)
            val combined = (if (offset == 0) page.movies else movies + page.movies).distinctBy { it.ratingKey }
            check(offset == 0 || offset == lastAppliedOffset || page.movies.isEmpty() || combined.size > movies.size) { "Plex 未返回新的影片，请刷新当前库" }
            movies = combined
            nextPageOffset = next; lastAppliedOffset = offset
            total = page.total
            pageState.completedRequest = requestKey
            if (resetPosition) {
                // Apply the position with the new data during the next measure, bypassing old keys.
                gridState.requestScrollToItem(0)
                listState.requestScrollToItem(0)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { libraryFailure = plexErrorMessage(error) }
        finally { libraryLoading = false }
    }

    LaunchedEffect(connectionIdentity, session.revision, movieKey, detailRefresh) {
        credentials ?: return@LaunchedEffect
        if (openTarget != null && openTarget.serverId != serverId) return@LaunchedEffect
        val key = movieKey ?: return@LaunchedEffect
        detailLoading = true; detailFailure = null; watchFailure = null
        if (detail?.ratingKey != key) detail = null
        try {
            session.cachedDetail(key)?.let { detail = it }
            detail = session.loadDetail(key)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { detailFailure = plexErrorMessage(error) }
        finally { detailLoading = false }
    }

    fun setWatched(movie: PlexMovie, watched: Boolean) {
        if (watchBusyKey != null) return
        watchBusyKey = movie.ratingKey; watchFailure = null
        scope.launch {
            try { session.setWatched(movie, watched) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { watchFailure = "观看状态未同步：${plexErrorMessage(error)}。请重试。" }
            finally { watchBusyKey = null }
        }
    }

    fun up() { if (onExternalDetailBack != null) { onExternalDetailBack(); return }; if (movieKey != null) movieKey = null else if (globalSearch) globalSearch = false else if (libraryKey != null) { libraryKey = null; query = ""; offset = 0 } else onRootBack?.invoke() }
    BackHandler(movieKey != null || libraryKey != null || globalSearch || onExternalDetailBack != null || onRootBack != null) { up() }
    Column(modifier.fillMaxSize()) {
        val nested = libraryKey != null || movieKey != null || globalSearch
        val backLabel = when {
            onExternalDetailBack != null -> externalBackLabel
            movieKey != null && globalSearch -> "返回搜索结果"
            movieKey != null && libraryKey != null -> "返回影片列表"
            globalSearch && libraryKey != null -> "返回影片列表"
            nested -> "返回媒体库"
            else -> rootBackLabel
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nested || onRootBack != null || onExternalDetailBack != null) IconButton(onClick = ::up, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowBack, backLabel, Modifier.size(22.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (movieKey != null) (if (seriesDetail) "电视剧详情" else "影片详情") else if (globalSearch) "全库搜索" else libraries.firstOrNull { it.key == libraryKey }?.title ?: "媒体库",
                    fontSize = if (nested) 21.sp else 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(credentials?.config?.name?.let { "Plex · $it" } ?: "你的 Plex 片库", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!nested && credentials != null) IconButton(onClick = { globalSearch = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Search, "搜索全部媒体库") }
            if (libraryKey != null && movieKey == null && !globalSearch && credentials != null) PlexLibraryActions(
                options, ::updateOptions, availableSorts, filters.active,
                onFilter = { filterDialog = true }, onGlobalSearch = { globalSearch = true; globalQuery = query },
                onRefresh = { offset = 0; forceLibraryRefresh = true; libraryRefresh++ }, loading = libraryLoading)
            if (movieKey != null && credentials != null) IconButton(onClick = {
                detailRefresh++
            }, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surface)) {
                Icon(Icons.Default.Refresh, if (seriesDetail || seriesLibrary) "刷新本剧" else "刷新资料", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val active = credentials
        if (active != null && failure != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$failure", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { session.reload() }, enabled = !loading) { Text("重试连接") }
        }
        watchFailure?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        when {
            loading && active == null -> PlexLoading()
            failure != null && active == null -> PlexMessage(failure!!, "去设置", onOpenSettings)
            active == null -> PlexMessage("尚未连接 Plex", "去设置", onOpenSettings)
            openTarget != null && active.config.id != openTarget.serverId -> PlexMessage("该影片的 Plex 片源尚未连接", "去设置", onOpenSettings)
            movieKey != null -> {
                if (detailLoading && detail != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                detailFailure?.let { Text(if (detail != null) "已显示保存的资料。$it" else it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (detail != null) {
                    if (seriesDetail) PlexSeriesDetail(active, detail!!, client, session = session, refreshKey = detailRefresh, initialSeasonIndex = requestedSeason,
                        onSetWatched = ::setWatched, watchBusyKey = watchBusyKey) { episode, part -> onPlay(active.config, episode, part) }
                    else PlexMovieDetail(active, detail!!, client, onSetWatched = ::setWatched, watchBusy = watchBusyKey != null) { movie, part -> onPlay(active.config, movie, part) }
                } else if (detailLoading) PlexLoading()
                if (detailFailure != null) TextButton(onClick = { detailRefresh++ }) { Text("重试资料") }
            }
            globalSearch -> PlexGlobalSearch(session, active, preferences, globalQuery, { globalQuery = it }, globalSearchState, globalListState, Modifier.weight(1f)) { detail = null; requestedSeason = null; movieKey = it.ratingKey }
            libraryKey == null -> {
                if (loading && libraries.isEmpty()) { PlexLoading(); return@Column }
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("movie" to "电影", "show" to "电视剧").forEach { (value, label) ->
                            val chosen = category == value
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .selectable(selected = chosen, role = Role.Tab, onClick = { category = value; preferences.saveCategory(category) })
                                .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (value == "show") Icons.Default.Tv else Icons.Default.Movie, null, Modifier.size(18.dp), tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val categoryLibraries = libraries.filter { it.type == category }
                if (categoryLibraries.isEmpty()) PlexMessage(if (category == "movie") "没有可访问的电影库。" else "没有可访问的电视剧库。", "去设置", onOpenSettings)
                else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (category == "show") "电视剧媒体库" else "电影媒体库", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${categoryLibraries.size} 个", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(categoryLibraries, key = { it.key }) { library ->
                        Card(onClick = { offset = 0; libraryKey = library.key }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                                    Icon(if (library.type == "show") Icons.Default.Tv else Icons.Default.VideoLibrary, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(library.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (library.type == "show") "电视剧库" else "电影库", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            else -> {
                PlexLibraryToolbar(query, { query = it.take(100); offset = 0 }, { recordSearch() }, searchHistory,
                    onHistorySelect = { query = it; offset = 0; recordSearch(it) },
                    onHistoryDelete = { term -> preferences.removeSearch(active.config.id, libraryKey!!, term); searchHistory = preferences.history(active.config.id, libraryKey!!) },
                    onHistoryClear = { preferences.clearHistory(active.config.id, libraryKey!!); searchHistory = emptyList() },
                    searchPlaceholder = if (seriesLibrary) "搜索这个电视剧库的剧名" else "搜索这个电影库的片名")
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (libraryLoading && movies.isEmpty()) "正在读取 Plex…" else if (movies.size < total) "${movies.size} / $total ${if (seriesLibrary) "部电视剧" else "部影片"}" else "${movies.size} ${if (seriesLibrary) "部电视剧" else "部影片"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (libraryLoading) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(14.dp), strokeWidth = 2.dp)
                }
                libraryFailure?.let { error ->
                    Text(error, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { forceLibraryRefresh = true; libraryRefresh++ }) { Text("重试") }
                }
                if (!libraryLoading && libraryFailure == null && movies.isEmpty()) PlexMessage(if (filters.active) "没有符合筛选条件的影片。" else if (query.isBlank()) { if (seriesLibrary) "这个电视剧库还没有剧集。" else "这个电影库还没有影片。" } else "没有找到匹配的影片。")
                else PlexMovieCollection(active, client, movies, options, gridState, listState, Modifier.weight(1f),
                    onMovieClick = { recordSearch(); detail = null; requestedSeason = null; movieKey = it.ratingKey }, hasMore = nextPageOffset != null,
                    loading = libraryLoading, loadFailed = libraryFailure != null, onLoadMore = {
                        val currentPageKey = "$libraryKey|${query.trim()}|${options.sort}|${options.descending}|$filters"
                        nextPageOffset?.takeIf { !libraryLoading && libraryFailure == null && displayedPageKey == currentPageKey }?.let {
                            libraryLoading = true; offset = it
                        }
                    })
            }
        }
    }
    if (filterDialog && selectedLibrary != null) PlexFilterDialog(session, selectedLibrary, filters,
        onDismiss = { filterDialog = false }, onApply = { filters = it; offset = 0; filterDialog = false })

}

@Composable
private fun PlexLoading() { Box(Modifier.fillMaxWidth().padding(45.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

@Composable
private fun PlexMessage(text: String, action: String? = null, onAction: () -> Unit = {}, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.VideoLibrary, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Button(onClick = onAction, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 13.dp)) { Text(it) } }
        secondary?.let { TextButton(onClick = onSecondary) { Text(it) } }
    }
}
