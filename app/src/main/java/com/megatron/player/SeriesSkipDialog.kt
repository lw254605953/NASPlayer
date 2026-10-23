package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SeriesSkipDialog(key: SeriesSkipKey, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SeriesSkipPreferences(context) }
    val initial = remember(key) { store.override(key) }
    val global = store.global()
    var follow by remember(key) { mutableStateOf(initial == null) }
    var intro by remember(key) { mutableStateOf((initial ?: global).introSeconds.toString()) }
    var outro by remember(key) { mutableStateOf((initial ?: global).outroSeconds.toString()) }
    val introValue = intro.toIntOrNull()?.takeIf { it in 0..3600 }
    val outroValue = outro.toIntOrNull()?.takeIf { it in 0..3600 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("本剧片头片尾") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("使用全局设置", Modifier.weight(1f))
                Switch(follow, { follow = it })
            }
            OutlinedTextField(if (follow) global.introSeconds.toString() else intro, { next -> if (next.length <= 4 && next.all(Char::isDigit)) intro = next },
                enabled = !follow, label = { Text("片头（秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !follow && introValue == null, supportingText = if (!follow && introValue == null) ({ Text("请输入0至3600秒") }) else null)
            OutlinedTextField(if (follow) global.outroSeconds.toString() else outro, { next -> if (next.length <= 4 && next.all(Char::isDigit)) outro = next },
                enabled = !follow, label = { Text("片尾（秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !follow && outroValue == null, supportingText = if (!follow && outroValue == null) ({ Text("请输入0至3600秒") }) else null)
        }
    }, confirmButton = { TextButton(enabled = follow || introValue != null && outroValue != null, onClick = {
        store.save(key, if (follow) null else EpisodeSkipTimes(introValue!!, outroValue!!)); onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
