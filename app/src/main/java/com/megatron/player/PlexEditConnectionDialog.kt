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
import com.megatron.player.plex.PlexClient
import com.megatron.player.plex.PlexCredentials
import com.megatron.player.plex.PlexServerConfig
import com.megatron.player.plex.PlexStore
import com.megatron.player.plex.plexErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlexEditConnectionDialog(
    config: PlexServerConfig,
    store: PlexStore,
    onDismiss: () -> Unit,
    onSaved: (PlexServerConfig, Boolean) -> Unit,
) {
    var name by remember(config.id) { mutableStateOf(config.name) }
    var address by remember(config.id) { mutableStateOf(HttpAddressValue.fromUrl(config.address)) }
    var token by remember(config.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save(verify: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val normalized = PlexClient.validateServerAddress(address.fullUrl()).toString()
                    if (verify) {
                        val currentCredentials = store.credentials(config.id)
                            ?: throw IllegalStateException("Plex 凭据不可用，请重新连接")
                        val nextToken = token.trim().ifEmpty { currentCredentials.token }
                        val verified = PlexClient(store.clientIdentifier).connect(normalized, nextToken, config.id, name.trim())
                        store.save(verified)
                        ensureActive()
                        verified.config to true
                    } else if (normalized == config.address && token.trim().isEmpty()) {
                        val renamed = store.rename(name)
                            ?: throw IllegalStateException("Plex 连接不存在，请重新连接")
                        ensureActive()
                        renamed to false
                    } else {
                        val currentCredentials = store.credentials(config.id)
                            ?: throw IllegalStateException("Plex 凭据不可用，请重新连接")
                        val next = config.copy(name = name.trim(), address = normalized)
                        val nextToken = token.trim().ifEmpty { currentCredentials.token }
                        store.save(PlexCredentials(next, nextToken))
                        ensureActive()
                        next to true
                    }
                }
                onSaved(result.first, result.second)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = plexErrorMessage(failure)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("编辑 Plex 连接") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, enabled = !busy)
                HttpAddressField(address, { address = it }, "Plex 服务器", "192.168.1.8:32400", enabled = !busy)
                OutlinedTextField(
                    token,
                    { token = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Plex token（留空保留）") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在保存…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && name.isNotBlank() && address.address.isNotBlank(), onClick = { save(verify = true) }) { Text("验证并保存") }
                TextButton(enabled = !busy && name.isNotBlank() && address.address.isNotBlank(), onClick = { save(verify = false) }) { Text("保存") }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
