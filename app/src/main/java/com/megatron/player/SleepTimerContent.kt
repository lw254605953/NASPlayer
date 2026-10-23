package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun sleepTimerText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
fun SleepTimerContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val timer = remember { SleepTimer.shared(context) }
    val state by timer.state.collectAsState()
    var hours by remember { mutableStateOf("00") }
    var minutes by remember { mutableStateOf("30") }
    val custom = sleepDurationMs(hours.toIntOrNull() ?: -1, minutes.toIntOrNull() ?: -1)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.active) {
            Text("剩余 ${sleepTimerText(state.remainingMs)}", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            TextButton(onClick = { timer.cancel(); onDone() }) { Text("取消定时") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(30 to "半小时", 60 to "1小时", 120 to "2小时").forEach { (value, label) ->
                OutlinedButton(onClick = { timer.start(value * 60_000L); onDone() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("时") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("分") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(onClick = { custom?.let(timer::start); onDone() }, enabled = custom != null, modifier = Modifier.fillMaxWidth()) { Text("设置定时") }
    }
}
