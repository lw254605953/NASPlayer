package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.megatron.player.source.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun SourceEditorDialog(existing: MediaSourceConfig?, store: SourceStore, onDismiss: () -> Unit, onSaved: (MediaSourceConfig) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "我的 WebDAV") }
    var address by remember { mutableStateOf(HttpAddressValue.fromUrl(existing?.address.orEmpty())) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    // Credentials never enter saved instance state or a playback URL.
    var password by remember { mutableStateOf("") }
    var replacePassword by remember { mutableStateOf(existing == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加 WebDAV" else "编辑 WebDAV") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, enabled = !busy)
            HttpAddressField(address, { address = it }, label = "WebDAV 服务器与路径", placeholder = "nas.example.com:5006/dav/", enabled = !busy)
            OutlinedTextField(username, { username = it }, label = { Text("用户名（可留空）") }, singleLine = true, enabled = !busy)
            OutlinedTextField(password, { password = it; replacePassword = true }, label = { Text(if (existing != null && !replacePassword) "密码（留空保留）" else "密码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            if (existing != null && !busy) TextButton(onClick = { password = ""; replacePassword = true }) { Text(if (replacePassword && password.isEmpty()) "已清除密码，保存后生效" else "清除已存密码") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("正在连接…", style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        if (existing == null) {
            TextButton(enabled = !busy && name.isNotBlank() && address.address.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val source = withContext(Dispatchers.IO) {
                            val client = WebDavClient()
                            val config = MediaSourceConfig(UUID.randomUUID().toString(), name.trim(), SourceKind.WEBDAV,
                                client.validateAddress(address.fullUrl()), username.trim())
                            client.list(SourceCredentials(config, password))
                            ensureActive()
                            store.save(config, password)
                            config
                        }
                        onSaved(source)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = sourceErrorMessage(failure) }
                    finally { busy = false }
                }
            }) { Text("连接并保存") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && name.isNotBlank() && address.address.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val source = withContext(Dispatchers.IO) {
                                val client = WebDavClient()
                                val config = MediaSourceConfig(existing.id, name.trim(), SourceKind.WEBDAV,
                                    client.validateAddress(address.fullUrl()), username.trim())
                                val secret = if (replacePassword) password else store.credentials(config.id)?.password.orEmpty()
                                client.list(SourceCredentials(config, secret))
                                ensureActive()
                                store.save(config, if (replacePassword) password else null)
                                config
                            }
                            onSaved(source)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = sourceErrorMessage(failure) }
                        finally { busy = false }
                    }
                }) { Text("验证并保存") }
                TextButton(enabled = !busy && name.isNotBlank() && address.address.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val source = withContext(Dispatchers.IO) {
                                val client = WebDavClient()
                                val config = MediaSourceConfig(existing.id, name.trim(), SourceKind.WEBDAV,
                                    client.validateAddress(address.fullUrl()), username.trim())
                                store.save(config, if (replacePassword) password else null)
                                config
                            }
                            onSaved(source)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = sourceErrorMessage(failure) }
                        finally { busy = false }
                    }
                }) { Text("保存") }
            }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

fun sourceErrorMessage(error: Exception): String = when {
    error is SecurityException -> "文件访问授权已失效，请重新选择本机目录。"
    error is javax.net.ssl.SSLException -> "无法验证 HTTPS 证书。请使用系统信任的证书或检查服务器配置。"
    error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException -> "连接超时，请确认服务器在线后重试。"
    error is java.net.UnknownHostException -> "找不到服务器，请检查地址和手机网络。"
    error is java.net.ConnectException -> "无法连接服务器，请检查地址、端口和网络。"
    error.message?.contains("401") == true || error.message?.contains("403") == true -> "认证失败或无目录权限，请检查用户名、密码和访问权限。"
    error.message?.contains("404") == true -> "目录不存在，请检查 WebDAV 地址及路径。"
    error is IllegalArgumentException -> error.message ?: "连接地址不正确。"
    else -> "无法读取片源，请检查 WebDAV 地址、网络和认证配置后重试。"
}
