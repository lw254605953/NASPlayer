package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun JumpToTimeDialog(currentMs: Long, durationMs: Long, onDismiss: () -> Unit, onJump: (Long) -> Unit) {
    val initial = remember { formatPlaybackTime(currentMs) }
    var input by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val target = parsePlaybackTimeMs(input.text)
        error = when {
            target == null -> "请输入秒数、分:秒或时:分:秒"
            target > durationMs -> "时间超出影片时长 ${formatPlaybackTime(durationMs)}"
            else -> null
        }
        if (target != null && error == null) onJump(target)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("跳转到指定时间") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前 ${formatPlaybackTime(currentMs)} · 总时长 ${formatPlaybackTime(durationMs)}")
            OutlinedTextField(value = input, onValueChange = { input = it; error = null }, label = { Text("播放时间") },
                placeholder = { Text("例如 01:23:45") }, singleLine = true, isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = ::submit) { Text("跳转") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
