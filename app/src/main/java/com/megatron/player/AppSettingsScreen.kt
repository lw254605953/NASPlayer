package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.plex.PlexBrowseSession
import com.megatron.player.source.MediaSourceConfig
import com.megatron.player.source.SourceKind

/** Settings index mirrors the approved prototype; connection actions stay on its detail page. */
@Composable
fun AppSettingsScreen(
    session: PlexBrowseSession,
    sources: List<MediaSourceConfig>,
    boundSourceId: String?,
    bindingFailure: String?,
    showContinue: Boolean,
    hasHistory: Boolean,
    page: String,
    modifier: Modifier = Modifier,
    onPageChange: (String) -> Unit,
    onShowContinueChange: (Boolean) -> Unit,
    onOpenSource: (MediaSourceConfig) -> Unit,
    onSourceMenu: (MediaSourceConfig) -> Unit,
    onAddSource: () -> Unit,
    onSourcesChanged: () -> Unit,
    onPreferences: () -> Unit,
    onHistory: () -> Unit,
    onProgressSync: () -> Unit,
    onClearHistory: () -> Unit,
    onFavorites: () -> Unit,
    onMusicDirectories: () -> Unit,
    onBackup: () -> Unit,
    onRootBack: (() -> Unit)? = null,
) {
    val config = session.config
    val bound = sources.firstOrNull { it.id == boundSourceId && it.kind == SourceKind.WEBDAV }
    val standalone = sources.filterNot { config != null && it.id == boundSourceId }
    var about by remember { mutableStateOf(false) }
    key(page) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page == "plex") {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPageChange("main") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
                        Text(config?.name ?: "连接 Plex", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { ChannelLabel("Plex 资料连接") }
                item { PlexSettingsPanel(session, onSourcesChanged) }
                if (bound != null) {
                    item { ChannelLabel("WebDAV 视频连接") }
                    item { ChannelGroup {
                        ChannelRow(bound.name, bound.address, Icons.Default.Storage, onClick = { onOpenSource(bound) },
                            trailing = { IconButton(onClick = { onSourceMenu(bound) }) { Icon(Icons.Default.MoreVert, "${bound.name}操作") } })
                    } }
                }
            } else if (page == "personal") {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPageChange("main") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
                        Text("个人设置", style = MaterialTheme.typography.titleLarge)
                    }
                }
                item { ChannelLabel("首页显示") }
                item { ChannelGroup {
                    ChannelRow("显示继续观看", null, onClick = { onShowContinueChange(!showContinue) },
                        trailing = { Switch(showContinue, onShowContinueChange) })
                } }
                item { ChannelGroup { ChannelRow("播放偏好", null, Icons.Default.Tune, onClick = onPreferences) } }
                item { ChannelLabel("观看记录") }
                item { ChannelGroup {
                    ChannelRow("观看历史", null, onClick = onHistory)
                    ChannelDivider()
                    ChannelRow("Plex 进度同步", null, onClick = onProgressSync)
                    ChannelDivider()
                    ChannelRow("清除最近播放记录", null, onClick = if (hasHistory) onClearHistory else null,
                        trailing = { if (hasHistory) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                            else Text("暂无记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                } }
            } else {
                item {
                    if (onRootBack == null) ChannelHeading("设置")
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRootBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上一页") }
                        Text("设置", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                item { ChannelLabel("片源管理") }
                item { ChannelGroup {
                    if (config != null) {
                        ChannelRow(config.name, when {
                            bindingFailure != null -> "视频来源需要修正"
                            bound != null -> "Plex / WebDAV"
                            boundSourceId != null -> "WebDAV 视频连接待配置"
                            else -> "Plex"
                        }, Icons.Default.Dns, onClick = { onPageChange("plex") })
                        ChannelDivider()
                    }
                    standalone.forEach { source ->
                        ChannelRow(source.name, if (source.kind == SourceKind.LOCAL) "本机目录" else "WebDAV",
                            if (source.kind == SourceKind.LOCAL) Icons.Default.Smartphone else Icons.Default.Storage,
                            onClick = { onOpenSource(source) }, trailing = {
                                IconButton(onClick = { onSourceMenu(source) }) { Icon(Icons.Default.MoreVert, "${source.name}操作") }
                            })
                        ChannelDivider()
                    }
                    ChannelRow("添加片源", icon = Icons.Default.Add, onClick = onAddSource)
                } }
                item { ChannelLabel("音乐") }
                item { ChannelGroup { ChannelRow("音乐目录", null, Icons.Default.LibraryMusic, onClick = onMusicDirectories) } }
                item { ChannelGroup { ChannelRow("模型设置", null, Icons.Default.AutoAwesome, beta = true, onClick = { onPageChange("ai") }) } }
                item { ChannelLabel("个人内容") }
                item { ChannelGroup {
                    ChannelRow("我的收藏", null, Icons.Default.FavoriteBorder, onClick = onFavorites)
                    ChannelDivider()
                    ChannelRow("个人设置", null, Icons.Default.ManageAccounts, onClick = { onPageChange("personal") })
                    ChannelDivider()
                    ChannelRow("备份与恢复", null, Icons.Default.Backup, onClick = onBackup)
                } }
                item { ChannelLabel("关于") }
                item { ChannelGroup { ChannelRow("NASPlayer", null, onClick = { about = true },
                    trailing = { Text(BuildConfig.VERSION_NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }) } }
            }
        }
    }
    if (about) BrandAboutDialog { about = false }
}
