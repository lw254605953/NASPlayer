package com.megatron.player

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.*
import com.megatron.player.source.MediaSourceConfig
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceUris
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val HomeSurface = Color(0xFF222527)
private val HomeMuted = Color(0xFFADAFAE)
private val HomeAccent = Color(0xFFE6BF7A)
private val HomeText = Color(0xFFF3F1EB)
private val HomeLine = Color.White.copy(alpha = .055f)

/** File-first home. Saved configuration is never presented as a live connectivity result. */
@Composable
fun AppHomeScreen(
    sources: List<MediaSourceConfig>,
    plexSession: PlexBrowseSession,
    boundSourceId: String?,
    bindingFailure: String?,
    history: List<RecentVideo>,
    modifier: Modifier = Modifier,
    showAllSources: Boolean = false,
    showContinueWatching: Boolean = true,
    onShowAllSources: () -> Unit,
    onAddSource: () -> Unit,
    onOpenSource: (MediaSourceConfig) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowContinue: () -> Unit,
    onOpenDetails: (PlexDetailTarget) -> Unit,
    onPlayPlex: (PlexServerConfig, PlexMovie, PlexPart) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
    onBack: () -> Unit,
    sourceFailures: Map<String, String> = emptyMap(),
) {
    val config = plexSession.config
    val bound = sources.firstOrNull { it.id == boundSourceId && it.kind == SourceKind.WEBDAV }
    val independent = sources.filterNot { config != null && it.id == boundSourceId }
    val groups = buildList {
        if (config != null) add(HomeSourceGroup("plex:${config.id}", plex = config, source = bound))
        independent.forEach { add(HomeSourceGroup("source:${it.id}", source = it)) }
    }
    val visibleGroups = if (showAllSources) groups else groups.take(3)
    val unfinished = remember(history) { unfinishedHistory(history) }
    val files = remember(unfinished, sources) { unfinished.filter { homeFileAvailable(it, sources) } }
    val serverId = config?.id
    var savedPlex by remember(serverId) { mutableStateOf<List<HomeResume>>(emptyList()) }
    var opening by remember(serverId) { mutableStateOf<String?>(null) }
    var lastAttempt by remember(serverId) { mutableStateOf<HomeResume?>(null) }
    var playFailure by remember(serverId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val needsFallback = plexSession.continueLoading || plexSession.continueFailure != null
    LaunchedEffect(serverId, plexSession.revision, history, needsFallback, showAllSources, showContinueWatching) {
        savedPlex = emptyList()
        if (showAllSources || !showContinueWatching || serverId == null || !needsFallback) return@LaunchedEffect
        savedPlex = unfinished.mapNotNull { recent ->
            val ref = PlexUris.parse(Uri.parse(recent.uri)) ?: return@mapNotNull null
            if (ref.serverId != serverId) return@mapNotNull null
            val cached = try {
                plexSession.cachedDetail(ref.ratingKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (cached?.watched == true) null else HomeResume(movie = cached, recent = recent, serverId = serverId)
        }
    }
    val serverResumes = if (plexSession.credentials?.config?.id == serverId && serverId != null) {
        groupedPlexContinueWatching(plexSession.continueWatching).map { HomeResume(movie = it, serverId = serverId) }
    } else emptyList()
    val resumes = (serverResumes + savedPlex.takeIf { needsFallback }.orEmpty() + files.map { HomeResume(recent = it) })
        .distinctBy { it.groupKey }.take(3)
    LaunchedEffect(resumes.map { it.key }) { playFailure = null }

    fun playResume(target: HomeResume) {
        if (opening != null) return
        opening = target.key; lastAttempt = target; playFailure = null
        scope.launch {
            try {
                if (target.serverId != null) {
                    val currentConfig = plexSession.credentials?.config
                        ?.takeIf { it.id == target.serverId }
                        ?: throw HomePlaybackFailure("Plex 连接尚未恢复，请稍后重试或前往设置。")
                    val key = target.movie?.ratingKey ?: target.recent?.let { PlexUris.parse(Uri.parse(it.uri))?.ratingKey }
                        ?: throw HomePlaybackFailure("影片记录无法读取，请从媒体库重新选择。")
                    val current = plexSession.loadDetail(key)
                    if (current.watched) {
                        plexSession.refreshContinueWatching()
                        throw HomePlaybackFailure("这部影片已标记看完，请从媒体库选择播放。")
                    }
                    val part = current.parts.firstOrNull() ?: throw HomePlaybackFailure("影片没有可播放的完整文件，请检查 Plex 资料。")
                    if (plexSession.credentials?.config?.id != currentConfig.id) throw HomePlaybackFailure("片源已更换，请重新选择影片。")
                    onPlayPlex(currentConfig, current, part)
                } else {
                    val recent = target.recent ?: return@launch
                    if (!homeFileAvailable(recent, sources)) throw HomePlaybackFailure("影片的片源已移除，请在设置中重新添加。")
                    onPlayRecent(recent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                playFailure = if (failure is HomePlaybackFailure) failure.message else plexErrorMessage(failure)
            } finally { opening = null }
        }
    }

    LazyColumn(
        modifier.fillMaxSize().background(PlayerTheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showAllSources) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowBack, "返回首页", tint = HomeText) }
                Text(if (showAllSources) "全部片源" else "NASPlayer", Modifier.weight(1f), color = HomeText,
                    fontSize = if (showAllSources) 21.sp else 26.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onAddSource, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = .035f), CircleShape).border(1.dp, HomeLine, CircleShape)) {
                    Icon(Icons.Default.Add, "添加片源", tint = HomeText, modifier = Modifier.size(23.dp))
                }
            }
        }
        if (bindingFailure != null && config == null) item(key = "binding-error") {
            HomeIssue(bindingFailure, "前往设置", onOpenSettings)
        }
        if (groups.isEmpty()) {
            item(key = "empty") { HomeEmptySources(onAddSource) }
        } else {
            if (!showAllSources) item(key = "sources-heading") {
                HomeSectionHeading("我的片源", if (groups.size > 3) "全部片源" else "管理", if (groups.size > 3) onShowAllSources else onOpenSettings)
            }
            items(visibleGroups, key = { it.key }) { group ->
                val plex = group.plex
                if (plex != null) {
                    val issue = bindingFailure ?: if (boundSourceId != null && group.source == null) "绑定的 WebDAV 已移除，请重新配置视频来源。" else null
                    HomeNasCard(plex, group.source, plexSession, issue, group.source?.id?.let(sourceFailures::get), onOpenSource, onOpenLibrary, onOpenSettings)
                } else group.source?.let { HomeStandaloneSource(it, sourceFailures[it.id], onOpenSource, onOpenSettings) }
            }
        }
        if (!showAllSources) {
            if (showContinueWatching && (groups.isNotEmpty() || resumes.isNotEmpty())) item(key = "resume") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    HomeSectionHeading("继续观看", "全部", onShowContinue)
                    if (resumes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            resumes.forEach { resume -> key(resume.key) {
                                val credentials = plexSession.credentials?.takeIf { it.config.id == resume.serverId }
                                val movie = resume.movie
                                ContinueWatchingCard(resume.title, resume.facts, resume.position, resume.duration,
                                    onPlay = { playResume(resume) }, busy = opening == resume.key, playEnabled = opening == null,
                                    onDetails = if (resume.serverId != null) ({
                                        val ref = resume.recent?.let { PlexUris.parse(Uri.parse(it.uri)) }
                                        val key = if (movie?.type == "episode") movie.grandparentRatingKey.ifBlank { movie.ratingKey }
                                            else movie?.ratingKey ?: ref?.ratingKey
                                        if (key != null) onOpenDetails(PlexDetailTarget(resume.serverId, key,
                                            movie?.takeIf { it.type == "episode" && it.parentIndex >= 0 }?.parentIndex))
                                    }) else null,
                                    detailsLabel = if (movie?.type == "episode") "剧集详情" else "影片详情",
                                    poster = if (credentials != null && movie != null) ({ art ->
                                        ContinueWatchingPoster(credentials, movie, plexSession, art)
                                    }) else null)
                            } }
                        }
                    } else {
                        Text(if (plexSession.continueLoading) "正在读取观看进度…" else "未完成影片将在这里出现。",
                            Modifier.padding(vertical = 17.dp), color = HomeMuted, fontSize = 12.sp)
                    }
                    if (plexSession.continueFailure != null) {
                        HomeIssue("观看进度暂时无法更新", "重试", { plexSession.refreshContinueWatching() }, enabled = !plexSession.continueLoading)
                    }
                    playFailure?.let { HomeIssue(it, "重试", { lastAttempt?.let(::playResume) }, enabled = opening == null) }
                }
            }

        }
    }
}

private data class HomeSourceGroup(val key: String, val plex: PlexServerConfig? = null, val source: MediaSourceConfig? = null)
private data class HomeResume(val movie: PlexMovie? = null, val recent: RecentVideo? = null, val serverId: String? = null) {
    val key: String get() = serverId?.let { "$it:${movie?.ratingKey ?: recent?.let { row -> PlexUris.parse(Uri.parse(row.uri))?.ratingKey } ?: recent?.uri}" } ?: recent?.uri.orEmpty()
    val groupKey: String get() = if (movie?.type == "episode" && movie.grandparentRatingKey.isNotBlank()) "$serverId:show:${movie.grandparentRatingKey}" else key
    val title: String get() = movie?.let { if (it.type == "episode") it.grandparentTitle.ifBlank { it.title } else it.title } ?: recent?.title.orEmpty()
    val position: Long get() = recent?.positionMs ?: movie?.viewOffsetMs ?: 0L
    val duration: Long get() = recent?.durationMs?.takeIf { it > 0 } ?: movie?.durationMs ?: 0L
    val facts: String get() = movie?.let {
        if (it.type == "episode") plexEpisodeLabel(it) + " · " + it.title
        else it.year.takeIf { year -> year > 0 }?.toString().orEmpty()
    } ?: if (serverId != null) "Plex · 本机保存进度" else "本机与文件"

}
private class HomePlaybackFailure(message: String) : Exception(message)

/** Tree-based history belongs to a saved directory; one-off local selections stay available. */
private fun homeFileAvailable(recent: RecentVideo, sources: List<MediaSourceConfig>): Boolean = runCatching {
    val uri = Uri.parse(recent.uri)
    when (uri.scheme) {
        "mpplex" -> false
        SourceUris.SCHEME -> SourceUris.parse(uri)?.first?.let { id -> sources.any { it.id == id && it.kind == SourceKind.WEBDAV } } == true
        "content" -> if (DocumentsContract.isTreeUri(uri)) {
            sources.any { source -> source.kind == SourceKind.LOCAL && runCatching {
                val tree = Uri.parse(source.address)
                tree.authority == uri.authority && DocumentsContract.getTreeDocumentId(tree) == DocumentsContract.getTreeDocumentId(uri)
            }.getOrDefault(false) }
        } else true
        "http", "https", "file" -> true
        else -> false
    }
}.getOrDefault(false)

@Composable
private fun HomeSectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HomeText)
        TextButton(onClick = onClick, contentPadding = PaddingValues(start = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(action, fontSize = 12.sp, color = HomeMuted)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(15.dp), tint = HomeMuted)
        }
    }
}

@Composable
private fun HomeNasCard(
    config: PlexServerConfig,
    source: MediaSourceConfig?,
    session: PlexBrowseSession,
    bindingFailure: String?,
    sourceFailure: String?,
    onOpenSource: (MediaSourceConfig) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    val hasFiles = source != null && bindingFailure == null
    val subtitle = when {
        bindingFailure != null -> "视频片源配置需要修正"
        sourceFailure != null -> "上次访问失败"
        session.failure != null -> "影片资料暂时无法更新"
        session.loading -> "正在更新影片资料…"
        hasFiles -> "Plex 影片资料 · WebDAV 文件播放"
        else -> "Plex"
    }
    Surface(Modifier.fillMaxWidth(), shape = shape, color = HomeSurface, border = BorderStroke(1.dp, HomeLine)) {
        Column {
            Row(Modifier.fillMaxWidth().clip(shape).clickable(role = Role.Button) {
                if (bindingFailure != null) onOpenSettings() else if (source != null) onOpenSource(source) else onOpenLibrary()
            }.heightIn(min = 82.dp).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(Modifier.size(41.dp).background(HomeAccent.copy(alpha = .065f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Dns, null, Modifier.size(24.dp), tint = HomeAccent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(config.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = HomeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, fontSize = 11.sp, color = if (bindingFailure != null || sourceFailure != null || session.failure != null) MaterialTheme.colorScheme.error else HomeMuted, maxLines = 2)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = HomeMuted.copy(alpha = .65f))
            }
            if (bindingFailure != null) HomeIssue(bindingFailure, "前往设置", onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            else if (sourceFailure != null && source != null) HomeSourceFailure(sourceFailure, { onOpenSource(source) }, onOpenSettings)
            else if (session.failure != null) HomeIssue("Plex 资料连接失败", "重试", { session.reload() }, enabled = !session.loading, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun HomeStandaloneSource(source: MediaSourceConfig, failure: String?, onOpen: (MediaSourceConfig) -> Unit, onOpenSettings: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = HomeSurface, border = BorderStroke(1.dp, HomeLine)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(source) }.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha = .025f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (source.kind == SourceKind.LOCAL) Icons.Default.Smartphone else Icons.Default.Storage, null, Modifier.size(22.dp), tint = Color(0xFFC2C5C2))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HomeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (failure != null) "上次访问失败" else if (source.kind == SourceKind.LOCAL) "本机目录" else "WebDAV", fontSize = 11.sp, color = if (failure != null) MaterialTheme.colorScheme.error else HomeMuted)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = HomeMuted.copy(alpha = .65f))
            }
            if (failure != null) HomeSourceFailure(failure, { onOpen(source) }, onOpenSettings)
        }
    }
}

@Composable
private fun HomeSourceFailure(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        HorizontalDivider(color = HomeLine)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("重试", fontSize = 12.sp, color = HomeAccent) }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "前往片源设置", Modifier.size(18.dp), tint = HomeMuted) }
        }
    }
}

@Composable
private fun HomeEmptySources(onAdd: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(17.dp), color = HomeSurface) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 25.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(41.dp).background(HomeAccent.copy(alpha = .065f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Dns, null, Modifier.size(24.dp), tint = HomeAccent)
            }
            Text("暂无片源", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = HomeText)
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = HomeAccent, contentColor = Color(0xFF211C13))) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加片源", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HomeIssue(message: String, action: String, onAction: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(start = 8.dp)) {
            Text(action, fontSize = 12.sp, color = HomeAccent)
        }
    }
}
