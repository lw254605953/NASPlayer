package com.megatron.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.megatron.player.backup.*
import com.megatron.player.source.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun BackupScreen(sources: List<MediaSourceConfig>, modifier: Modifier = Modifier, onBack: () -> Unit,
    onConnect: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { BackupManager.shared(context) }
    val scope = rememberCoroutineScope()
    val status by manager.status.collectAsState()
    val available = sources.filter { it.kind == SourceKind.WEBDAV }
    var target by remember { mutableStateOf(manager.target()) }
    var setting by remember { mutableStateOf(!manager.configured()) }
    var sourceId by remember { mutableStateOf(target?.sourceId ?: available.firstOrNull()?.id.orEmpty()) }
    var path by remember { mutableStateOf(target?.path.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordAgain by remember { mutableStateOf("") }
    var passwordSetup by remember { mutableStateOf(false) }
    var usePassword by remember { mutableStateOf(manager.passwordProtected()) }
    var folders by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var backups by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var newDirectory by remember { mutableStateOf(false) }
    var directoryName by remember { mutableStateOf("NASPlayer备份") }
    var restore by remember { mutableStateOf<BrowserEntry?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }
    BackHandler { if (!restoring) { if (setting && manager.configured()) setting = false else onBack() } }
    LaunchedEffect(available) { if (available.none { it.id == sourceId }) { sourceId = available.firstOrNull()?.id.orEmpty(); path = "" } }
    LaunchedEffect(sourceId, path, setting, refresh, status.lastSuccess) {
        loading = true; error = null
        try {
            if (setting && sourceId.isNotBlank()) folders = manager.directories(sourceId, path)
            else if (!setting && target != null) backups = manager.versions(target!!)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取备份目录，请检查WebDAV连接与目录权限" }
        finally { loading = false }
    }
    fun save(enteredPassword: String?) {
        scope.launch {
            loading = true; error = null
            try {
                val selected = BackupTarget(sourceId, path)
                val existing = manager.versions(selected)
                manager.configure(selected, enteredPassword)
                target = selected; backups = existing; setting = false; passwordSetup = false; password = ""; passwordAgain = ""
                manager.backupNow()
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "备份设置无效" }
            catch (_: Exception) { error = "设置失败，请检查WebDAV连接与目录权限" }
            finally { loading = false }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (setting && manager.configured()) setting = false else onBack() }, enabled = !restoring) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置")
                }
                Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
            }
        }
        when {
            available.isEmpty() -> item {
                Text("请先连接WebDAV")
                Button(onClick = onConnect) { Text("连接WebDAV") }
            }
            setting -> {
                item { Text("设置备份目录", style = MaterialTheme.typography.titleMedium) }
                item { ChannelGroup {
                    available.forEach { source -> ChannelRow(source.name, if (source.id == sourceId) "已选择" else null, Icons.Default.Storage,
                        onClick = { sourceId = source.id; path = "" }) }
                } }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (path.isBlank()) "/" else path, Modifier.weight(1f))
                        if (path.isNotBlank()) TextButton(onClick = { path = path.substringBeforeLast('/', "") }, enabled = !loading) { Text("上一级") }
                        IconButton(onClick = { newDirectory = true }, enabled = !loading) { Icon(Icons.Default.CreateNewFolder, "新建备份目录") }
                    }
                }
                folders.forEach { folder -> item(key = folder.path) {
                    ChannelRow(folder.name, null, Icons.Default.Folder, onClick = if (loading) null else ({ path = folder.path }))
                } }
                item { Button(onClick = { password = ""; passwordAgain = ""; usePassword = manager.passwordProtected(); passwordSetup = true },
                    enabled = !loading && sourceId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("选择当前目录") } }
            }
            else -> {
                item { ChannelGroup {
                    ChannelRow("备份目录", available.firstOrNull { it.id == target?.sourceId }?.name?.let { "$it / ${target?.path.orEmpty()}" } ?: "连接已移除", Icons.Default.Folder,
                        onClick = { setting = true })
                    ChannelDivider()
                    ChannelRow("最近备份", if (status.lastSuccess > 0) DateFormat.getDateTimeInstance().format(Date(status.lastSuccess)) else "尚未备份", Icons.Default.Backup)
                } }
                item { Button(onClick = manager::backupNow, enabled = !status.busy && !restoring, modifier = Modifier.fillMaxWidth()) { Text(if (status.busy) "正在备份…" else "立即备份") } }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("备份版本", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新备份版本") }
                } }
                if (backups.isEmpty() && !loading) item { Text("暂无备份") }
                backups.forEach { entry -> item(key = entry.path) {
                    ChannelRow(DateFormat.getDateTimeInstance().format(Date(entry.name.substring(43, 56).toLong())), "${(entry.size + 1023) / 1024} KB", Icons.Default.Restore,
                        onClick = { restore = entry; restorePassword = "" })
                } }
            }
        }
        if (loading || restoring) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        (error ?: status.error)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
    }
    if (passwordSetup) AlertDialog(onDismissRequest = { if (!loading) passwordSetup = false }, title = { Text("备份密码") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置密码", Modifier.weight(1f))
                Switch(usePassword, { usePassword = it }, enabled = !loading)
            }
            if (usePassword) {
                OutlinedTextField(password, { password = it }, label = { Text(if (manager.passwordProtected()) "新密码（留空保留）" else "密码（8至128位）") },
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading)
                if (password.isNotEmpty() || !manager.passwordProtected()) OutlinedTextField(passwordAgain, { passwordAgain = it }, label = { Text("确认密码") },
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading)
            } else Text("不设置密码时，NAS密码和Plex令牌会以明文保存在备份中。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        val valid = !usePassword || (manager.passwordProtected() && password.isEmpty()) || (password.length in 8..128 && password == passwordAgain)
        TextButton(enabled = !loading && valid, onClick = {
            save(if (!usePassword) "" else if (password.isEmpty() && manager.passwordProtected()) null else password)
        }) { Text(if (loading) "正在保存…" else "保存") }
    }, dismissButton = { TextButton(onClick = { passwordSetup = false; password = ""; passwordAgain = "" }, enabled = !loading) { Text("取消") } })
    if (newDirectory) AlertDialog(onDismissRequest = { newDirectory = false }, title = { Text("新建备份目录") }, text = {
        OutlinedTextField(directoryName, { directoryName = it }, label = { Text("目录名称") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = directoryName.isNotBlank(), onClick = {
        newDirectory = false; scope.launch {
            loading = true
            try { manager.createDirectory(sourceId, path, directoryName); path = listOf(path, directoryName).filter(String::isNotBlank).joinToString("/"); refresh++ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法新建目录，请检查写入权限" }
            finally { loading = false }
        }
    }) { Text("创建") } }, dismissButton = { TextButton(onClick = { newDirectory = false }) { Text("取消") } })
    restore?.let { entry -> AlertDialog(onDismissRequest = { if (!restoring) restore = null }, title = { Text("恢复备份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将替换当前配置并关闭应用，重新打开后生效。")
            OutlinedTextField(restorePassword, { restorePassword = it }, label = { Text("备份密码（加密备份需填写）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
    }, confirmButton = { TextButton(enabled = !restoring && (restorePassword.isEmpty() || restorePassword.length in 8..128), onClick = {
        scope.launch {
            if (com.megatron.player.cast.CastController.shared(context).state.value.active) { error = "请先结束投屏再恢复"; restore = null; return@launch }
            restoring = true; error = null
            try {
                val prepared = manager.prepareRestore(entry, restorePassword)
                BackupRestoreGuard.restoring = true
                com.megatron.player.music.metadata.MusicOrganizeEngine.shared(context).cancelAndJoin()
                com.megatron.player.music.MusicPlaybackGate.player?.stop()
                manager.applyRestore(prepared, restorePassword)
                (context as? Activity)?.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "恢复失败，请检查备份密码及文件完整性"; restore = null; BackupRestoreGuard.restoring = false }
            finally { restoring = false }
        }
    }) { Text(if (restoring) "正在恢复…" else "恢复并关闭应用") } }, dismissButton = { TextButton(onClick = { restore = null }, enabled = !restoring) { Text("取消") } }) }
}
