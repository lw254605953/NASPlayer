package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Standalone settings surface; callers can place it in the app settings page or a player dialog. */
@Composable
fun PlaybackPreferencesPanel(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { PlaybackPreferencesStore(context) }
    var preferences by remember { mutableStateOf(store.read()) }
    var audioMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var skipField by remember { mutableStateOf<String?>(null) }
    var skipSeconds by remember { mutableStateOf("") }
    var sizeMenu by remember { mutableStateOf(false) }
    val languages = PlaybackLanguage.values().toList()

    fun update(next: PlaybackPreferences) {
        preferences = next
        store.save(next)
        onChanged()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("播放偏好", style = MaterialTheme.typography.titleMedium)
        PreferenceMenu("音轨语言", languages.firstOrNull { it.code == preferences.audioLanguage }?.label ?: "自动", audioMenu, { audioMenu = it }) {
            languages.forEach { language -> DropdownMenuItem(text = { Text(language.label) }, onClick = { update(preferences.copy(audioLanguage = language.code)); audioMenu = false }) }
        }
        PreferenceMenu("字幕语言", languages.firstOrNull { it.code == preferences.subtitleLanguage }?.label ?: "自动", subtitleMenu, { subtitleMenu = it }) {
            languages.forEach { language -> DropdownMenuItem(text = { Text(language.label) }, onClick = { update(preferences.copy(subtitleLanguage = language.code)); subtitleMenu = false }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("字幕", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = preferences.subtitlesEnabled, onCheckedChange = { update(preferences.copy(subtitlesEnabled = it)) })
        }
        PreferenceMenu("字幕字号", preferences.subtitleSize.label, sizeMenu, { sizeMenu = it }) {
            SubtitleSize.values().forEach { size -> DropdownMenuItem(text = { Text(size.label) }, onClick = { update(preferences.copy(subtitleSize = size)); sizeMenu = false }) }
        }
        HorizontalDivider()
        Text("电视剧", style = MaterialTheme.typography.titleSmall)
        listOf("片头" to preferences.episodeIntroSeconds, "片尾" to preferences.episodeOutroSeconds).forEach { (label, seconds) ->
            OutlinedButton(onClick = { skipField = label; skipSeconds = seconds.toString() }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f)); Text("${seconds} 秒")
                }
            }
        }
    }
    skipField?.let { field ->
        val parsed = skipSeconds.toIntOrNull()?.takeIf { it in 0..3600 }
        AlertDialog(onDismissRequest = { skipField = null }, title = { Text("电视剧$field") }, text = {
            OutlinedTextField(value = skipSeconds, onValueChange = { next ->
                if (next.length <= 4 && next.all(Char::isDigit)) skipSeconds = next
            }, label = { Text("秒数") }, suffix = { Text("秒") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = skipSeconds.isNotEmpty() && parsed == null,
                supportingText = if (skipSeconds.isNotEmpty() && parsed == null) ({ Text("请输入0至3600秒") }) else null)
        }, confirmButton = { TextButton(enabled = parsed != null, onClick = {
            parsed?.let { value -> update(if (field == "片头") preferences.copy(episodeIntroSeconds = value) else preferences.copy(episodeOutroSeconds = value)) }
            skipField = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { skipField = null }) { Text("取消") } })
    }
}

@Composable
private fun PreferenceMenu(label: String, value: String, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(value, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded, { onExpandedChange(false) }, content = content)
        }
    }
}
