package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.megatron.player.ai.*
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit, backLabel: String = "返回设置") {
    val context = LocalContext.current
    val store = remember { QwenConfigStore(context) }
    val organizer = remember { com.megatron.player.music.metadata.MusicOrganizeEngine.shared(context) }
    var config by remember { mutableStateOf(store.read()) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(store.hasKey()) }
    var limit by remember { mutableStateOf(config.dailyLimit.toString()) }
    var region by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(): Boolean = try {
        if (organizer.state.value.running) { if (!config.enabled) organizer.pause() else error("请先暂停资料整理") }
        val value = config.copy(dailyLimit = limit.toIntOrNull() ?: error("请输入每日请求上限"))
        check(!value.enabled || key.isNotBlank() || hasKey) { "请先填写 API Key" }
        store.save(value, key.takeIf(String::isNotBlank)); key = ""; config = value; hasKey = store.hasKey(); result = "已保存"; true
    } catch (e: Exception) { result = e.message?.takeIf { it.length <= 80 } ?: "保存失败"; false }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, backLabel) }; BetaFeatureLabel("模型设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp)) }
        ChannelGroup { ChannelRow("Qwen", trailing = { Switch(config.enabled, { config = config.copy(enabled = it) }, enabled = !busy) }) }
        Box {
            OutlinedButton(onClick = { region = true }, enabled = !busy) { Text(if (config.baseUrl == QwenPolicy.BEIJING) "北京" else "新加坡") }
            DropdownMenu(region, { region = false }) { listOf("北京" to QwenPolicy.BEIJING, "新加坡" to QwenPolicy.SINGAPORE).forEach { (name, url) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { config = config.copy(baseUrl = url); region = false })
            } }
        }
        Text(config.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(config.model, { config = config.copy(model = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true, enabled = !busy)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "API Key（已保存，填写以替换）" else "API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
        OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("每日请求上限") }, supportingText = { Text("0 表示不限制") }, singleLine = true, enabled = !busy)
        Text("今日已请求 ${store.requestsToday()} 次", style = MaterialTheme.typography.bodySmall)
        Text("使用对应地域的标准按量 API Key。Key 仅保存在本机，不进入备份。验证连接会调用一次模型并计入上限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { save() }, enabled = !busy) { Text("保存") }
            OutlinedButton(onClick = {
                if (save()) { busy = true; result = null; scope.launch {
                    try { QwenClient(context, store).testConnection(); result = "连接成功" }
                    catch (e: Exception) { result = e.message ?: "连接失败" }
                    finally { busy = false }
                } }
            }, enabled = !busy && config.enabled) { Text("验证连接") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ChannelGroup {
            ChannelRow("工作边界", icon = Icons.Default.Security, onClick = { rules = true })
            if (hasKey) ChannelRow("清除 API Key", icon = Icons.Default.KeyOff, onClick = { if (!busy) clearing = true })
        }
    }
    if (rules) AlertDialog(onDismissRequest = { rules = false }, title = { Text("音乐资料整理 · 工作边界") }, text = {
        Text(remember { QwenSkill.read(context) }, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = { rules = false }) { Text("关闭") } })
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清除 API Key？") }, confirmButton = {
        TextButton(onClick = { busy = true; clearing = false; scope.launch { try { organizer.cancelAndJoin(); store.clearKey(); hasKey = false; config = store.read(); key = ""; result = "已清除" } catch (_: Exception) { result = "清除失败，请重试" } finally { busy = false } } }) { Text("清除") }
    }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
