package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import kotlinx.coroutines.flow.first

@Composable
fun PlexLibraryToolbar(
    query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit,
    history: List<String>, onHistorySelect: (String) -> Unit, onHistoryDelete: (String) -> Unit, onHistoryClear: () -> Unit,
    searchPlaceholder: String = "搜索这个电影库的片名",
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth().padding(horizontal = 20.dp).onFocusChanged { focused = it.isFocused },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        placeholder = { Text(searchPlaceholder, style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
        shape = RoundedCornerShape(15.dp), textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f), unfocusedBorderColor = Color.Transparent),
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "清空搜索", Modifier.size(19.dp)) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit(); focus.clearFocus() }))
    if (focused && query.isBlank() && history.isNotEmpty()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近搜索", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = onHistoryClear) { Text("清空历史") }
            }
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(history, key = { it }) { term ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { onHistorySelect(term); focus.clearFocus() }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(term, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onHistoryDelete(term) }) { Icon(Icons.Default.Close, "删除搜索 $term", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun PlexLibraryActions(
    options: PlexLibraryOptions, onOptionsChange: (PlexLibraryOptions) -> Unit,
    availableSorts: List<PlexSort>, filtersActive: Boolean,
    onFilter: () -> Unit, onGlobalSearch: () -> Unit, onRefresh: () -> Unit, loading: Boolean,
) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { focus.clearFocus(); menu = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, "媒体库更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("排序 · ${options.sort.label}") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { menu = false; sorting = true })
            DropdownMenuItem(text = { Text(sortDirection(options)) }, leadingIcon = { Icon(if (options.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null) },
                onClick = { menu = false; onOptionsChange(options.copy(descending = !options.descending)) })
            DropdownMenuItem(text = { Text("视图 · ${options.view.label}") }, leadingIcon = { Icon(Icons.Default.GridView, null) }, onClick = { menu = false; viewMenu = true })
            DropdownMenuItem(text = { Text(if (filtersActive) "筛选 · 已开启" else "筛选") }, leadingIcon = { Icon(Icons.Default.FilterList, null) }, onClick = { menu = false; onFilter() })
            DropdownMenuItem(text = { Text("搜索全部媒体库") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; onGlobalSearch() })
            DropdownMenuItem(text = { Text("刷新当前库") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, enabled = !loading, onClick = { menu = false; onRefresh() })
        }
    }
    if (viewMenu) AlertDialog(onDismissRequest = { viewMenu = false }, title = { Text("影片视图") }, text = {
        Column {
            PlexViewMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { onOptionsChange(options.copy(view = mode)); viewMenu = false }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = options.view == mode, onClick = { onOptionsChange(options.copy(view = mode)); viewMenu = false })
                    Text(mode.label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { viewMenu = false }) { Text("关闭") } })
    if (sorting) AlertDialog(onDismissRequest = { sorting = false }, title = { Text("影片排序") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            availableSorts.forEach { sort ->
                Row(Modifier.fillMaxWidth().clickable { onOptionsChange(options.copy(sort = sort, descending = sort.defaultDescending)); sorting = false }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = options.sort == sort, onClick = { onOptionsChange(options.copy(sort = sort, descending = sort.defaultDescending)); sorting = false })
                    Text(sort.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text("${sortDirection(options)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { sorting = false }) { Text("关闭") } })
}

private fun sortDirection(options: PlexLibraryOptions): String = when (options.sort) {
    PlexSort.TITLE -> if (options.descending) "Z 到 A" else "A 到 Z"
    PlexSort.RATING, PlexSort.AUDIENCE_RATING -> if (options.descending) "高分在前" else "低分在前"
    PlexSort.DURATION -> if (options.descending) "长片在前" else "短片在前"
    else -> if (options.descending) "较新在前" else "较早在前"
}

@Composable
fun PlexMovieCollection(
    credentials: PlexCredentials, client: PlexClient, movies: List<PlexMovie>, options: PlexLibraryOptions,
    gridState: LazyGridState, listState: LazyListState, modifier: Modifier = Modifier,
    onMovieClick: (PlexMovie) -> Unit, hasMore: Boolean, loading: Boolean, loadFailed: Boolean, onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(options.view, movies.size, hasMore, loading, loadFailed) {
        if (!hasMore || loading || loadFailed || movies.isEmpty()) return@LaunchedEffect
        val threshold = (movies.size - if (options.view == PlexViewMode.LIST) 6 else options.view.columns * 3).coerceAtLeast(0)
        snapshotFlow {
            if (options.view == PlexViewMode.LIST) listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.first { it >= threshold }
        loadMore()
    }
    if (options.view == PlexViewMode.LIST) LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(movies, key = { it.ratingKey }) { movie ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable { onMovieClick(movie) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlexMediaThumbnail(credentials, movie, client, Modifier.width(66.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), compact = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(movie.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(movie.year.takeIf { it > 0 }?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (movie.genres.isNotEmpty()) Text(movie.genres.take(3).joinToString(" / "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (movie.type == "show" && movie.leafCount > 0) Text("${movie.viewedLeafCount.coerceIn(0, movie.leafCount)} / ${movie.leafCount} 集", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!movie.watched && movie.viewOffsetMs > 0 && movie.type != "show") Text("观看至 ${formatPlaybackTime(movie.viewOffsetMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (hasMore && loading) item { LoadingMovies() }
    } else {
        val compact = options.view == PlexViewMode.POSTER_FOUR
        LazyVerticalGrid(columns = GridCells.Fixed(options.view.columns), state = gridState, modifier = modifier,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(movies, key = { it.ratingKey }) { movie ->
                Column(Modifier.clip(RoundedCornerShape(if (compact) 8.dp else 13.dp)).clickable { onMovieClick(movie) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlexMediaThumbnail(credentials, movie, client, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(if (compact) 8.dp else 13.dp)), compact = compact)
                    Text(movie.title, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium, fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(movie.year.takeIf { it > 0 }?.toString().orEmpty(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasMore && loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { LoadingMovies() }
        }
    }
}

@Composable
private fun LoadingMovies() {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
