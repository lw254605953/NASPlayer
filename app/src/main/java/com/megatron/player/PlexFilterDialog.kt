package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException

@Composable
fun PlexFilterDialog(session: PlexBrowseSession, library: PlexLibrary, selected: PlexLibraryFilter,
    onDismiss: () -> Unit, onApply: (PlexLibraryFilter) -> Unit) {
    var draft by remember { mutableStateOf(selected) }
    var facets by remember { mutableStateOf(PlexLibraryFacets()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(library.key, retry) {
        loading = true; failure = null
        try { facets = session.loadFacets(library) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = plexErrorMessage(e) }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("筛选${if (library.type == "show") "电视剧" else "影片"}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FacetSelector("观看状态", draft.watched.label, PlexWatchFilter.entries.map { it.name to it.label }) { name ->
                draft = draft.copy(watched = PlexWatchFilter.valueOf(name))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { retry++ }) { Text("重试读取类型与年份") } }
            FacetSelector("类型", facets.genres.firstOrNull { it.key == draft.genre }?.label ?: if (draft.genre.isBlank()) "全部类型" else "已选类型",
                listOf("" to "全部类型") + facets.genres.map { it.key to it.label }) { draft = draft.copy(genre = it) }
            FacetSelector("年份", facets.years.firstOrNull { it.key == draft.year }?.label ?: draft.year.ifBlank { "全部年份" },
                listOf("" to "全部年份") + facets.years.map { it.key to it.label }) { draft = draft.copy(year = it) }
            FacetSelector("最低观众评分", if (draft.minimumRating == 0) "不限" else "${draft.minimumRating} 分及以上",
                (0..10).map { it.toString() to if (it == 0) "不限" else "$it 分及以上" }) { draft = draft.copy(minimumRating = it.toInt()) }
        }
    }, confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("应用") } },
        dismissButton = { Row { TextButton(onClick = { draft = PlexLibraryFilter() }) { Text("重置") }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable
private fun FacetSelector(label: String, value: String, values: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 300.dp)) {
                values.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(key) }) }
            }
        }
    }
}
