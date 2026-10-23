package com.megatron.player.cast

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun CastPanel(controller: CastController, uri: Uri, title: String, positionMs: Long, durationMs: Long, mime: String,
    autoConnect: Boolean = true, onSelected: () -> Unit = {}) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val devices by controller.devices.collectAsState()
    val discovering by controller.discovering.collectAsState()
    val discoveryError by controller.discoveryError.collectAsState()
    val googleMessage by controller.googleMessage.collectAsState()
    var googleExpanded by remember { mutableStateOf(false) }
    val selected by rememberUpdatedState(onSelected)
    var permissionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var permissionAutoConnect by remember { mutableStateOf(false) }
    fun discover(connectRemembered: Boolean) {
        scope.launch {
            val found = controller.discover().await()
            // This coroutine is cancelled with the chooser, so late search results cannot cast.
            if (connectRemembered && !controller.isCasting(uri.toString())) {
                controller.rememberedDevice(found)?.let { device ->
                    controller.stage(uri, title, positionMs, durationMs, mime)
                    if (controller.selectDlna(device)) selected()
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) { permissionError = null; discover(permissionAutoConnect) }
        else permissionError = "未允许附近设备访问，可在系统设置授权后重试。"
    }
    LaunchedEffect(uri, title, positionMs, durationMs, mime) { controller.stage(uri, title, positionMs, durationMs, mime) }
    // A new Google connection also dismisses the chooser; an existing active session does not.
    LaunchedEffect(state.busy, state.uri) { if (state.busy && state.uri == uri.toString()) selected() }
    fun search(connectRemembered: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) discover(connectRemembered)
        else { permissionAutoConnect = connectRemembered; permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES) }
    }
    LaunchedEffect(uri) { search(autoConnect) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DLNA 电视", Modifier.weight(1f), fontSize = 16.sp)
            TextButton(onClick = { search() }, enabled = !discovering) { Text(if (discovering) "搜索中…" else "搜索电视") }
        }
        if (discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
        devices.forEach { device ->
            OutlinedButton(onClick = { controller.stage(uri, title, positionMs, durationMs, mime); if (controller.selectDlna(device)) selected() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tv, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text(device.name, Modifier.weight(1f)); Text("DLNA", fontSize = 10.sp)
            }
        }
        if (!discovering && devices.isEmpty()) Text("未发现设备", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        TextButton(onClick = { googleExpanded = !googleExpanded }) { Text(if (googleExpanded) "收起 Google Cast" else "Google Cast（可选）") }
        if (googleExpanded) {
            if (googleMessage == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AndroidView(factory = { viewContext -> safeGoogleCastButton(viewContext, controller::googleUnavailable) }, modifier = Modifier.size(52.dp))
                    Text("选择设备", fontSize = 13.sp)
                }
                if (controller.hasGoogleConnection()) Button(onClick = controller::loadConnectedGoogle, modifier = Modifier.fillMaxWidth()) { Text("在已连接的电视播放本视频") }
            } else {
                Text(googleMessage!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { controller.prepareGoogle() }) { Text("启用 / 重试 Google Cast") }
            }
        }
        (permissionError ?: discoveryError ?: state.error)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun CastMiniPlayer(controller: CastController, onOpen: () -> Unit) {
    val state by controller.state.collectAsState()
    if (!state.active && !state.busy) return
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .semantics { contentDescription = "查看电视投屏" }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CastConnected, null, Modifier.padding(12.dp).size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, fontSize = 12.sp)
                Text("${state.device} · ${if (state.busy) "正在连接" else if (state.playing) "正在投屏" else "已暂停"}", maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
