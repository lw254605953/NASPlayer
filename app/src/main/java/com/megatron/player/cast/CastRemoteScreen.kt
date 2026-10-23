package com.megatron.player.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megatron.player.JumpToTimeDialog
import com.megatron.player.formatPlaybackTime

/** TV controls never call the phone's track selector, volume or video layout. */
@Composable
fun CastRemoteScreen(
    state: CastState, title: String, positionMs: Long, durationMs: Long,
    onBack: () -> Unit, onChooseDevice: () -> Unit, onToggle: () -> Unit,
    onSeek: (Long) -> Unit, onStop: () -> Unit, onShowInfo: (() -> Unit)? = null, onSleepTimer: (() -> Unit)? = null, episodes: @Composable () -> Unit = {},
) {
    var scrub by remember(state.uri) { mutableStateOf<Long?>(null) }
    var jump by remember { mutableStateOf(false) }
    val gold = Color(0xFFE6BF7A)
    val muted = Color(0xFFADAFAE)
    val usable = state.confirmed && state.active
    val position = if (usable || state.completed) state.positionMs else positionMs
    val duration = castDurationMs(state.durationMs, durationMs)
    val seekEnabled = usable && !state.busy && state.canSeek && duration > 0
    LaunchedEffect(seekEnabled) { if (!seekEnabled) { scrub = null; jump = false } }
    Column(Modifier.fillMaxSize().background(Color(0xFF101213)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
            Text("电视遥控", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color.White)
            onSleepTimer?.let { IconButton(onClick = it) { Icon(Icons.Default.Timer, "定时关闭", tint = gold) } }
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "关闭投屏", tint = Color.White) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color(0xFF1B1E20)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (usable) Icons.Default.CastConnected else Icons.Default.Tv, null, Modifier.size(56.dp), tint = gold)
                Text(when { state.device.isNotBlank() -> state.device; state.busy -> "连接中"; state.error != null -> "连接失败"; else -> "未连接" },
                    style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (state.protocol.isNotBlank()) Text(state.protocol, color = muted, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (usable) TextButton(onClick = onChooseDevice, enabled = !state.busy) { Text("切换设备") }
                    if (onShowInfo != null) TextButton(onClick = onShowInfo) { Text("播放信息") }
                }
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = muted)
                Text(when { state.busy -> "连接中…"; usable && state.playing -> "播放中";
                    usable -> "已暂停"; state.completed -> "已播完"; else -> "未连接" },
                    color = muted, style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = gold)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (usable) {
            Column {
                Slider(value = if (duration > 0) ((scrub ?: position).toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { scrub = (it * duration).toLong() },
                    onValueChangeFinished = { scrub?.let(onSeek); scrub = null }, enabled = seekEnabled,
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(scrub ?: position), color = gold)
                    Text(if (duration > 0) formatPlaybackTime(duration) else "未知时长", color = muted)
                }
            }
            Button(onClick = onToggle, enabled = !state.busy && (state.canPause || !state.playing),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp)); Text(if (state.playing) "暂停电视播放" else "继续电视播放")
            }
            OutlinedButton(onClick = { jump = true }, enabled = seekEnabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Schedule, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("跳转到时间")
            }
        } else if (!state.busy) {
            Button(onClick = onChooseDevice, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.Cast, null); Spacer(Modifier.width(8.dp)); Text("选择电视")
            }
        }
        episodes()
        }
    }
    if (jump && seekEnabled) JumpToTimeDialog(position, duration, onDismiss = { jump = false }, onJump = { onSeek(it); jump = false })
}
