package com.megatron.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun PlexSeriesDetail(credentials: PlexCredentials, show: PlexMovie, client: PlexClient, session: PlexBrowseSession, refreshKey: Int = 0, initialSeasonIndex: Int? = null, onSetWatched: ((PlexMovie, Boolean) -> Unit)? = null, watchBusyKey: String? = null, onPlay: (PlexMovie, PlexPart) -> Unit) {
    val context = LocalContext.current
    val viewPreferences = remember { PlexEpisodeViewPreferences(context) }
    var selectedSeason by rememberSaveable(credentials.config.id, show.ratingKey) { mutableStateOf<String?>(null) }
    var viewModeName by rememberSaveable(credentials.config.id, show.ratingKey, "episode-view") { mutableStateOf(viewPreferences.get(credentials.config.id, show.ratingKey).name) }
    var seasons by remember(show.ratingKey) { mutableStateOf<List<PlexMovie>>(emptyList()) }
    var episodes by remember(show.ratingKey) { mutableStateOf<List<PlexMovie>>(emptyList()) }
    var loading by remember(show.ratingKey) { mutableStateOf(true) }
    var failure by remember(show.ratingKey) { mutableStateOf<String?>(null) }
    var playFailure by remember(show.ratingKey) { mutableStateOf<String?>(null) }
    var opening by remember(show.ratingKey) { mutableStateOf<String?>(null) }
    var initialSeasonApplied by rememberSaveable(show.ratingKey, initialSeasonIndex) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var seasonMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun applyEpisodes(children: List<PlexMovie>, all: List<PlexMovie>) {
        val derived = all.groupBy { it.parentRatingKey.ifBlank { "season:${it.parentIndex}" } }.map { (key, items) ->
            PlexMovie(key, items.first().parentTitle, type = "season", index = items.first().parentIndex, leafCount = items.size)
        }
        seasons = (children.ifEmpty { derived }).sortedWith(compareBy<PlexMovie> { if (it.index < 0) Int.MAX_VALUE else it.index }.thenBy { it.ratingKey })
        episodes = orderedPlexEpisodes(all)
        val requested = if (!initialSeasonApplied) seasons.firstOrNull { it.index == initialSeasonIndex } else null
        if (requested != null) { selectedSeason = requested.ratingKey; initialSeasonApplied = true }
        if (seasons.none { it.ratingKey == selectedSeason }) {
            val next = resumePlexEpisode(episodes)
            selectedSeason = seasons.firstOrNull { it.ratingKey == next?.parentRatingKey || (next != null && it.index == next.parentIndex) }?.ratingKey
                ?: seasons.firstOrNull { it.index > 0 }?.ratingKey ?: seasons.firstOrNull()?.ratingKey
        }
    }
    LaunchedEffect(credentials.config.id, show.ratingKey, revision, refreshKey, session.revision) {
        loading = true; failure = null
        try {
            session.cachedEpisodes(show.ratingKey)?.let { cached ->
                applyEpisodes(session.cachedSeasons(show.ratingKey).orEmpty(), cached)
                loading = false
            }
            val all = session.loadEpisodes(show.ratingKey)
            val children = try { session.loadSeasons(show.ratingKey) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (all.isEmpty()) throw error else emptyList() }
            applyEpisodes(children, all)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = plexErrorMessage(error) }
        finally { loading = false }
    }
    fun play(episode: PlexMovie) {
        if (opening != null) return
        opening = episode.ratingKey; playFailure = null
        scope.launch {
            try {
                val current = session.loadDetail(episode.ratingKey)
                if (current.type != "episode" || (current.grandparentRatingKey.isNotBlank() && current.grandparentRatingKey != show.ratingKey)) { playFailure = "Plex 返回的剧集信息不匹配"; return@launch }
                val part = current.parts.firstOrNull() ?: run { playFailure = "这一集没有可直接播放的完整文件"; return@launch }
                onPlay(current, part)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { playFailure = plexErrorMessage(error) }
            finally { opening = null }
        }
    }
    val selected = seasons.firstOrNull { it.ratingKey == selectedSeason }
    val viewMode = runCatching { PlexEpisodeViewMode.valueOf(viewModeName) }.getOrDefault(PlexEpisodeViewMode.POSTER_FOUR)
    val numberColumns = (((LocalConfiguration.current.screenWidthDp - 40 + 8) / (48 + 8)).coerceIn(1, 8))
    val visibleEpisodes = episodes.filter { episode ->
        selected != null && (episode.parentRatingKey == selected.ratingKey || (episode.parentRatingKey.isBlank() && episode.parentIndex == selected.index))
    }
    val next = resumePlexEpisode(episodes)
    PlexMovieDetail(credentials, show, client, showPlayback = false, extraContent = {
        item {
            if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("正在读取季与集…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            failure?.let { Text(if (episodes.isNotEmpty()) "已显示保存的剧集。$it" else it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text("重试") } }
            if (!loading && failure == null && episodes.isEmpty()) Text("暂无剧集")
            if (next != null) Button(onClick = { play(next) }, enabled = opening == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if (opening == next.ratingKey) "正在打开…" else "${if (next.viewOffsetMs > 0 && !next.watched) "继续观看" else "播放"} ${plexEpisodeLabel(next)}", maxLines = 1)
            }
            playFailure?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (seasons.isNotEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { seasonMenu = true }, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(selected?.let(::seasonName) ?: "选择季", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(3.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(seasonMenu, { seasonMenu = false }) {
                            seasons.forEach { season ->
                                DropdownMenuItem(text = { Text(seasonName(season)) }, leadingIcon = { if (season.ratingKey == selectedSeason) Icon(Icons.Default.Check, null) },
                                    onClick = { selectedSeason = season.ratingKey; seasonMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${visibleEpisodes.size} 集", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        TextButton(onClick = { viewMenu = true }, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(viewMode.label, fontSize = 12.sp)
                            Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ViewModule, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(viewMenu, { viewMenu = false }) {
                            PlexEpisodeViewMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = { if (mode == viewMode) Icon(Icons.Default.Check, null) },
                                    onClick = {
                                        viewModeName = mode.name
                                        viewPreferences.save(credentials.config.id, show.ratingKey, mode)
                                        viewMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        plexEpisodePresentation(credentials, client, visibleEpisodes, viewMode, opening, numberColumns, ::play, onSetWatched, watchBusyKey)
    }) { _, _ -> }
}

private fun seasonName(season: PlexMovie): String = when {
    season.index == 0 -> "特别篇"
    season.index > 0 -> "第${season.index}季"
    else -> season.title.ifBlank { "未分季" }
}
