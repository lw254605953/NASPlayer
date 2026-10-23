package com.megatron.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.megatron.player.plex.PlexServerConfig
import com.megatron.player.plex.PlexWebDavBinding
import com.megatron.player.plex.PlexWebDavBindingStore
import com.megatron.player.source.MediaSourceConfig
import com.megatron.player.source.SourceCredentials
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceStore
import com.megatron.player.source.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Plex keeps its media identity; only the source of video bytes is configured here. */
@Composable
fun PlexWebDavBindingDialog(
    config: PlexServerConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val sourceStore = remember { SourceStore(context) }
    val bindingStore = remember { PlexWebDavBindingStore(context) }
    val previousResult = remember(config.id) { runCatching { bindingStore.read(config.id) } }
    val previous = previousResult.getOrNull()
    var sourceRevision by remember { mutableIntStateOf(0) }
    var editingSource by remember { mutableStateOf<MediaSourceConfig?>(null) }
    val sources = remember(config.id, sourceRevision) { sourceStore.list().filter { it.kind == SourceKind.WEBDAV } }
    var useWebDav by remember(config.id) { mutableStateOf(previous != null || previousResult.isFailure) }
    var newSource by remember(config.id) { mutableStateOf(sources.isEmpty()) }
    var selectedId by remember(config.id) { mutableStateOf(previous?.sourceId ?: sources.firstOrNull()?.id.orEmpty()) }
    var sourceMenu by remember { mutableStateOf(false) }
    val newId = remember(config.id) { UUID.randomUUID().toString() }
    var newName by remember(config.id) { mutableStateOf("${config.name} · WebDAV") }
    var address by remember(config.id) { mutableStateOf(HttpAddressValue()) }
    var username by remember(config.id) { mutableStateOf("") }
    // Passwords stay in ephemeral state and SourceStore's encrypted credential storage.
    var password by remember(config.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(previousResult.exceptionOrNull()?.message) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    fun draftCredentials(): SourceCredentials {
        if (!newSource) {
            val current = sourceStore.credentials(selectedId)
                ?: throw IllegalArgumentException("请选择一个 WebDAV 连接")
            require(current.config.kind == SourceKind.WEBDAV) { "请选择有效的 WebDAV 连接" }
            return current
        }
        require(newName.isNotBlank()) { "请填写 WebDAV 显示名称" }
        val normalized = WebDavClient().validateAddress(address.fullUrl())
        return SourceCredentials(MediaSourceConfig(newId, newName.trim(), SourceKind.WEBDAV, normalized, username.trim()), password)
    }

    fun save() {
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!useWebDav) {
                        bindingStore.remove(config.id)
                    } else {
                        val credentials = draftCredentials()
                        ensureActive()
                        if (newSource) sourceStore.save(credentials.config, credentials.password)
                        bindingStore.save(config.id, PlexWebDavBinding(
                            sourceId = credentials.config.id,
                            mappings = previous?.mappings.orEmpty(),
                            autoMatch = true,
                            mediaRoot = "",
                        ))
                    }
                }
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = bindingErrorMessage(failure)
            } finally { busy = false }
        }
    }

    fun testConnection() {
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WebDavClient().list(draftCredentials()) }
                notice = "连接成功"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = bindingErrorMessage(failure)
            } finally { busy = false }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp)) {
                Text("视频来源", style = MaterialTheme.typography.headlineSmall)
                Text(config.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BindingChoice("Plex 提供视频", "", !useWebDav, !busy) { useWebDav = false; error = null; notice = null }
                    BindingChoice("WebDAV 提供视频", "", useWebDav, !busy) { useWebDav = true; error = null; notice = null }
                    if (useWebDav) {
                        HorizontalDivider()
                        Text("WebDAV 连接", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !newSource, enabled = !busy && sources.isNotEmpty(), onClick = { newSource = false; error = null; notice = null }, label = { Text("已有连接") })
                            FilterChip(selected = newSource, enabled = !busy, onClick = { newSource = true; error = null; notice = null }, label = { Text("新建 WebDAV") })
                        }
                        if (!newSource) {
                            val selected = sources.firstOrNull { it.id == selectedId }
                            Box {
                                OutlinedButton(onClick = { sourceMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(selected?.name ?: "选择 WebDAV 连接")
                                }
                                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                    sources.forEach { source ->
                                        DropdownMenuItem(text = { Text(source.name) }, onClick = { selectedId = source.id; sourceMenu = false; notice = null })
                                    }
                                }
                            }
                            selected?.let {
                                Text(it.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { editingSource = it }, enabled = !busy) { Text("编辑 WebDAV 地址和账号") }
                            }
                            if (selected == null && previous != null) Text("原绑定连接已移除，请重新选择。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("WebDAV 显示名称") }, singleLine = true, enabled = !busy)
                            HttpAddressField(address, { address = it; notice = null }, label = "WebDAV 地址、端口与路径", placeholder = "nas.example.com:5006/dav/", enabled = !busy)
                            OutlinedTextField(username, { username = it; notice = null }, Modifier.fillMaxWidth(), label = { Text("用户名（可留空）") }, singleLine = true, enabled = !busy)
                            OutlinedTextField(password, { password = it; notice = null }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        }
                        TextButton(onClick = ::testConnection, enabled = !busy) { Text("测试 WebDAV 连接") }
                    }
                    if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在处理…", style = MaterialTheme.typography.bodySmall)
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    TextButton(onClick = ::save, enabled = !busy) { Text("保存") }
                }
            }
        }
    }
    editingSource?.let { existing ->
        SourceEditorDialog(existing, sourceStore, onDismiss = { editingSource = null }, onSaved = { saved ->
            selectedId = saved.id
            sourceRevision++
            editingSource = null
            notice = "WebDAV 连接已保存"
        })
    }
}

@Composable
private fun BindingChoice(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun bindingErrorMessage(failure: Exception): String = when (failure) {
    is IllegalArgumentException -> failure.message ?: "请检查 WebDAV 连接配置。"
    else -> sourceErrorMessage(failure)
}
