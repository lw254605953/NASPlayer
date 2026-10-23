package com.megatron.player

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class PlexDetailTarget(val serverId: String, val ratingKey: String, val seasonIndex: Int? = null, val sequence: Long = System.nanoTime()) : java.io.Serializable

/** Server ordering is authoritative; a show occupies one resume card. */
fun groupedPlexContinueWatching(items: List<PlexMovie>): List<PlexMovie> = items.filter {
    !it.watched && it.type in setOf("movie", "episode") &&
        (it.type == "episode" || it.viewOffsetMs > 0) &&
        (it.durationMs <= 0 || it.viewOffsetMs.toDouble() / it.durationMs < .95)
}.distinctBy { continueGroup(it) }

private fun continueGroup(movie: PlexMovie): String =
    if (movie.type == "episode" && movie.grandparentRatingKey.isNotBlank()) "show:${movie.grandparentRatingKey}" else "item:${movie.ratingKey}"

fun unfinishedHistory(items: List<RecentVideo>): List<RecentVideo> = items.filter {
    it.positionMs > 0 && (it.durationMs <= 0 || it.positionMs.toDouble() / it.durationMs < .95)
}.sortedByDescending { it.updatedAt }

@Composable
fun ContinueWatchingScreen(
    session: PlexBrowseSession,
    history: List<RecentVideo>,
    modifier: Modifier = Modifier,
    onPlayPlex: (PlexServerConfig, PlexMovie, PlexPart) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
    onOpenDetails: (PlexDetailTarget) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val active = session.credentials
    var opening by remember { mutableStateOf<String?>(null) }
    var playFailure by remember { mutableStateOf<String?>(null) }
    var savedPlex by remember { mutableStateOf<List<Pair<RecentVideo, PlexMovie?>>>(emptyList()) }
    val unfinished = unfinishedHistory(history)
    LaunchedEffect(active?.config?.id, session.revision, history) {
        savedPlex = unfinished.mapNotNull { recent ->
            val ref = PlexUris.parse(Uri.parse(recent.uri)) ?: return@mapNotNull null
            if (ref.serverId != active?.config?.id) return@mapNotNull null
            recent to session.cachedDetail(ref.ratingKey)
        }.filterNot { it.second?.watched == true }
    }
    val fromServer = groupedPlexContinueWatching(session.continueWatching)
    val seen = fromServer.map(::continueGroup).toMutableSet()
    val savedFallback = if (session.continueFailure != null || session.continueLoading) savedPlex.filter { (_, movie) ->
        movie == null || seen.add(continueGroup(movie))
    } else emptyList()
    val files = unfinished.filter { PlexUris.parse(Uri.parse(it.uri)) == null }

    fun play(movie: PlexMovie) {
        val credentials = active ?: return
        if (opening != null) return
        opening = movie.ratingKey; playFailure = null
        scope.launch {
            try {
                val current = session.loadDetail(movie.ratingKey)
                val part = current.parts.firstOrNull() ?: error("没有可直接播放的完整文件")
                if (session.credentials?.config?.id == credentials.config.id) onPlayPlex(credentials.config, current, part)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { playFailure = plexErrorMessage(failure) }
            finally { opening = null }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回首页") } }
                Text("继续观看", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (active != null) IconButton(onClick = { session.refreshContinueWatching() }, enabled = !session.continueLoading) { Icon(Icons.Default.Refresh, "刷新继续观看") }
            }
            if (session.continueLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            session.continueFailure?.let {
                Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { session.refreshContinueWatching() }, enabled = !session.continueLoading) { Text("重试") }
            }
            playFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (active != null && fromServer.isNotEmpty()) item { Text(active.config.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(fromServer, key = { "plex:${it.ratingKey}" }) { movie ->
            val credentials = active ?: return@items
            ContinueWatchingCard(
                title = if (movie.type == "episode") movie.grandparentTitle.ifBlank { movie.title } else movie.title,
                subtitle = if (movie.type == "episode") plexEpisodeLabel(movie) + " · " + movie.title else movie.year.takeIf { it > 0 }?.toString().orEmpty(),
                positionMs = movie.viewOffsetMs, durationMs = movie.durationMs,
                onPlay = { play(movie) }, busy = opening == movie.ratingKey, playEnabled = opening == null,
                onDetails = { onOpenDetails(PlexDetailTarget(credentials.config.id,
                    if (movie.type == "episode") movie.grandparentRatingKey.ifBlank { movie.ratingKey } else movie.ratingKey,
                    movie.parentIndex.takeIf { movie.type == "episode" && it >= 0 })) },
                detailsLabel = if (movie.type == "episode") "剧集详情" else "影片详情",
                poster = { art -> ContinueWatchingPoster(credentials, movie, session, art) },
            )
        }
        if (savedFallback.isNotEmpty()) item { Text("本机保存的 Plex 进度", style = MaterialTheme.typography.titleMedium) }
        items(savedFallback, key = { "saved:${it.first.uri}" }) { (recent, _) -> ContinueFileRow(recent, onPlayRecent) }
        if (files.isNotEmpty()) item { Text("本机与文件", style = MaterialTheme.typography.titleMedium) }
        items(files, key = { it.uri }) { ContinueFileRow(it, onPlayRecent) }
        if (fromServer.isEmpty() && savedFallback.isEmpty() && files.isEmpty() && !session.continueLoading) item {
            Text("暂无继续观看", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.config == null) TextButton(onClick = onOpenSettings) { Text("去设置连接 Plex") }
        }
    }
}

@Composable
private fun ContinueFileRow(recent: RecentVideo, onPlay: (RecentVideo) -> Unit) {
    ContinueWatchingCard(recent.title, "本机与文件", recent.positionMs, recent.durationMs, onPlay = { onPlay(recent) })
}
