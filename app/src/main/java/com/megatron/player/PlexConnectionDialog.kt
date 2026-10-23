package com.megatron.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*
import kotlinx.coroutines.*

@Composable
fun PlexConnectionDialog(onDismiss: () -> Unit, onConnected: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlexStore(context) }
    val client = remember { PlexClient(store.clientIdentifier) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<PlexServerOption>>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(HttpAddressValue()) }
    var token by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun connect(location: String, secret: String, name: String = "") {
        job = scope.launch {
            busy = true; error = null; message = "正在连接 Plex…"
            try {
                val credentials = withContext(Dispatchers.IO) { client.connect(location, secret.trim(), name = name) }
                ensureActive()
                withContext(Dispatchers.IO) { store.save(credentials) }
                onConnected()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = plexErrorMessage(failure) }
            finally { busy = false; message = null }
        }
    }

    AlertDialog(onDismissRequest = { job?.cancel(); onDismiss() }, title = { Text("连接 Plex") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (servers.isEmpty() && !manual) Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                job = scope.launch {
                    busy = true; error = null; message = "正在申请授权…"
                    try {
                        val pin = withContext(Dispatchers.IO) { client.createPin() }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pin.authUrl)))
                        message = "等待授权…"
                        val waitingSeconds = if (pin.expiresInSeconds > 0) pin.expiresInSeconds.coerceAtMost(600) else 300
                        val accountToken = withTimeout(waitingSeconds * 1000L) {
                            var result: String? = null
                            while (result == null) { delay(1000); result = withContext(Dispatchers.IO) { client.pollPin(pin) } }
                            result
                        }
                        message = "正在查找你的服务器…"
                        servers = withContext(Dispatchers.IO) { client.servers(accountToken) }
                        if (servers.isEmpty()) error = "账号下没有可连接的 Plex 服务器。请确认服务器已绑定此账号，也可手动连接。"
                    } catch (_: TimeoutCancellationException) { error = "授权等待已结束，请重新授权。" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = plexErrorMessage(failure) }
                    finally { busy = false; message = null }
                }
            }) { Text("使用 Plex 账号授权") }
            servers.forEach { server ->
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                server.connections.filterNot { it.relay }.sortedBy { !it.local }.forEach { connection ->
                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { connect(connection.address, server.token, server.name) }) {
                        Column { Text(if (connection.local) "局域网连接" else "服务器直连")
                            Text("${Uri.parse(connection.address).host.orEmpty()} · ${if (connection.address.startsWith("https")) "HTTPS" else "HTTP"}", style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    }
                }
                if (server.connections.none { !it.relay }) Text("仅发现中继地址", style = MaterialTheme.typography.bodySmall)
            }
            if (!busy) TextButton(onClick = { manual = !manual; error = null }) { Text(if (manual) "返回账号授权" else "手动连接") }
            if (manual) {
                HttpAddressField(address, { address = it }, "Plex 服务器", "192.168.1.8:32400", enabled = !busy)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("服务器访问 Token") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = { connect(address.fullUrl(), token) }, enabled = !busy && address.address.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("连接并保存") }
            }
            message?.let { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(it, style = MaterialTheme.typography.bodySmall) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) TextButton(onClick = { job?.cancel(); busy = false; message = null }) { Text("停止等待") }
        }
    }, confirmButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("关闭") } })
}
