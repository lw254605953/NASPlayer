package com.megatron.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val ALL_LIBRARIES_SCOPE = "all_libraries_search"
internal class PlexGlobalSearchState {
    val groups = mutableStateMapOf<String, PlexSearchGroup>()
    var searchedQuery = ""
    var revision = -1
    var generation = 0
    fun matches(term: String, currentRevision: Int, libraryKeys: List<String>): Boolean =
        searchedQuery == term && revision == currentRevision && groups.keys.toSet() == libraryKeys.toSet()
}

@Composable
internal fun PlexGlobalSearch(session: PlexBrowseSession, credentials: PlexCredentials, preferences: PlexLibraryPreferences,
    query: String, onQuery: (String) -> Unit, state: PlexGlobalSearchState, listState: LazyListState,
    modifier: Modifier = Modifier, onMovie: (PlexMovie) -> Unit) {
    val libraries = session.libraries
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var history by remember(credentials.config.id) { mutableStateOf(preferences.history(credentials.config.id, ALL_LIBRARIES_SCOPE)) }
    fun record() {
        preferences.recordSearch(credentials.config.id, ALL_LIBRARIES_SCOPE, query)
        history = preferences.history(credentials.config.id, ALL_LIBRARIES_SCOPE)
    }
    suspend fun load(library: PlexLibrary, offset: Int) {
        val generation = state.generation
        val old = state.groups[library.key] ?: PlexSearchGroup()
        state.groups[library.key] = old.copy(loading = true, failure = null)
        try {
            val page = session.searchLibrary(library, query.trim(), offset)
            if (generation != state.generation) return
            state.groups[library.key] = mergePlexSearchPage(old, page, offset)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == state.generation) state.groups[library.key] = old.copy(loading = false, failure = plexErrorMessage(e)) }
    }
    LaunchedEffect(query.trim(), session.revision, libraries.map { it.key }) {
        val term = query.trim()
        val sameSearch = state.matches(term, session.revision, libraries.map { it.key })
        val requested = if (sameSearch) libraries.filter { state.groups[it.key]?.loading == true } else libraries
        if (sameSearch && requested.isEmpty()) return@LaunchedEffect
        state.generation++
        if (!sameSearch) { state.groups.clear(); state.searchedQuery = term; state.revision = session.revision }
        if (term.isBlank()) return@LaunchedEffect
        requested.forEach { state.groups[it.key] = if (sameSearch) state.groups.getValue(it.key).copy(loading = true) else PlexSearchGroup(loading = true) }
        if (!sameSearch) { delay(350); listState.requestScrollToItem(0) }
        val gate = Semaphore(3)
        coroutineScope { requested.forEach { library -> launch { gate.withPermit { load(library, if (sameSearch) state.groups[library.key]?.nextOffset ?: 0 else 0) } } } }

    }
    Column(modifier) {
        OutlinedTextField(query, { onQuery(it.take(100)) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            singleLine = true, shape = RoundedCornerShape(15.dp), placeholder = { Text("搜索全部电影库和电视剧库") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "清空全库搜索") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { record(); focus.clearFocus() }))
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (query.isBlank()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全库搜索历史", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (history.isNotEmpty()) TextButton(onClick = { preferences.clearHistory(credentials.config.id, ALL_LIBRARIES_SCOPE); history = emptyList() }) { Text("清空") }
                } }
                if (history.isEmpty()) item { Text("暂无搜索历史", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(history, key = { "history:$it" }) { term -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(term, Modifier.weight(1f).clickable { onQuery(term); preferences.recordSearch(credentials.config.id, ALL_LIBRARIES_SCOPE, term); history = preferences.history(credentials.config.id, ALL_LIBRARIES_SCOPE); focus.clearFocus() }.padding(vertical = 12.dp))
                    IconButton(onClick = { preferences.removeSearch(credentials.config.id, ALL_LIBRARIES_SCOPE, term); history = preferences.history(credentials.config.id, ALL_LIBRARIES_SCOPE) }) { Icon(Icons.Default.Close, "删除全库搜索 $term") }
                } }
            } else {
                libraries.forEach { library ->
                    val group = state.groups[library.key] ?: PlexSearchGroup(loading = true)
                    item(key = "header:${library.key}") { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${library.title} · ${if (library.type == "show") "电视剧" else "电影"}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (group.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("${group.total} 部", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    items(group.movies, key = { "${library.key}:${it.ratingKey}" }) { movie ->
                        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable { record(); onMovie(movie) }, shape = RoundedCornerShape(13.dp)) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlexMediaThumbnail(credentials, movie, session.client, Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(7.dp)), compact = true)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(movie.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(movie.year.takeIf { it > 0 }?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "footer:${library.key}") {
                        when {
                            group.failure != null -> Column { Text(group.failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { scope.launch { load(library, group.nextOffset) } }, enabled = !group.loading) { Text(if (group.loading) "正在重试…" else "重试此库") } }
                            !group.loading && group.movies.isEmpty() -> Text("没有匹配结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            group.nextOffset < group.total -> TextButton(onClick = { scope.launch { load(library, group.nextOffset) } }, enabled = !group.loading, modifier = Modifier.fillMaxWidth()) { Text(if (group.loading) "正在读取…" else "加载此库更多结果") }
                        }
                    }
                }
            }
        }
    }
}
