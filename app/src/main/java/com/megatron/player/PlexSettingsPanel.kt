package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.PlexBrowseSession
import com.megatron.player.plex.PlexStore
import com.megatron.player.plex.PlexWebDavBindingStore
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceStore

@Composable
fun PlexSettingsPanel(session: PlexBrowseSession, onSourcesChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { PlexStore(context) }
    val bindingStore = remember { PlexWebDavBindingStore(context) }
    val sourceStore = remember { SourceStore(context) }
    var maintenanceExpanded by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var configuringVideo by remember { mutableStateOf(false) }
    var bindingAfterConnect by remember { mutableStateOf(false) }
    var bindingRevision by remember { mutableIntStateOf(0) }
    val bindingResult = remember(session.config?.id, bindingRevision) { runCatching { session.config?.id?.let(bindingStore::read) } }
    val binding = bindingResult.getOrNull()
    val videoSource = binding?.let { sourceStore.find(it.sourceId) }
    val sourceAvailable = videoSource?.kind == SourceKind.WEBDAV
    var displayName by remember(session.config?.id, session.config?.name) { mutableStateOf(session.config?.name.orEmpty()) }
    LaunchedEffect(bindingAfterConnect, session.config?.id) {
        if (bindingAfterConnect && session.config != null) {
            bindingAfterConnect = false
            configuringVideo = true
        }
    }
    ChannelGroup {
        ChannelRow(displayName.ifBlank { "连接 Plex" }, when {
            session.loading -> "正在读取媒体库…"
            session.failure != null -> session.failure!!
            session.config != null -> "Plex · ${session.libraries.size} 个媒体库"
            else -> "未连接"
        }, Icons.Default.VideoLibrary, onClick = { if (session.config == null) connecting = true else editing = true })
        if (session.config != null) {
            ChannelDivider()
            ChannelRow("视频来源", when {
                bindingResult.isFailure -> "绑定配置需修正"
                binding == null -> "Plex 原文件"
                !sourceAvailable -> "WebDAV 连接已移除，请重新选择"
                else -> "WebDAV · ${videoSource?.name}"
            }, Icons.Default.FolderOpen, onClick = { configuringVideo = true })
            ChannelDivider()
            ChannelRow("连接维护", null, Icons.Default.Settings,
                onClick = { maintenanceExpanded = !maintenanceExpanded }, trailing = { Icon(if (maintenanceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) })
            if (maintenanceExpanded) {
                ChannelDivider()
                ChannelRow(if (session.loading) "正在更新媒体库…" else "刷新媒体库列表", null, Icons.Default.Refresh,
                    onClick = if (session.loading) null else ({ session.reload() }))
                ChannelDivider()
                ChannelRow("重新连接", null, Icons.Default.Link, onClick = { connecting = true })
                ChannelDivider()
                ChannelRow("断开连接", null, Icons.Default.LinkOff, onClick = { disconnecting = true })
            }
        }
    }
    if (connecting) PlexConnectionDialog({ connecting = false }) {
        bindingAfterConnect = session.config == null
        connecting = false
        session.reload()
    }
    if (configuringVideo) {
        session.config?.let { config ->
            PlexWebDavBindingDialog(config, onDismiss = { configuringVideo = false }, onSaved = {
                bindingRevision++
                onSourcesChanged()
                configuringVideo = false
            })
        }
    }
    if (editing) {
        val config = session.config
        if (config != null) PlexEditConnectionDialog(config, store, { editing = false }) { saved, connectionChanged ->
            displayName = saved.name
            editing = false
            if (connectionChanged) session.reload() else session.rename(saved.name)
        }
    }
    if (disconnecting) AlertDialog(onDismissRequest = { disconnecting = false }, title = { Text("断开 Plex？") },
        text = { Text("将移除本机连接及凭据。") },
        confirmButton = { TextButton(onClick = { session.disconnect(); disconnecting = false }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { disconnecting = false }) { Text("取消") } })
}
