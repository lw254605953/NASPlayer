package com.megatron.player

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*
import java.security.MessageDigest
import java.util.Locale

class PlexVersionPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("plex_versions_v1", Context.MODE_PRIVATE)
    private fun key(config: PlexServerConfig, movie: PlexMovie): String = MessageDigest.getInstance("SHA-256")
        .digest("${config.machineIdentifier}|${movie.ratingKey}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun selected(config: PlexServerConfig, movie: PlexMovie): PlexPart? {
        val remembered = prefs.getString(key(config, movie), null)
        return movie.parts.firstOrNull { it.key == remembered } ?: movie.parts.firstOrNull()
    }
    fun save(config: PlexServerConfig, movie: PlexMovie, part: PlexPart) {
        if (movie.parts.any { it.key == part.key }) prefs.edit().putString(key(config, movie), part.key).apply()
    }
}

fun plexVersionDescription(part: PlexPart): String = listOfNotNull(
    part.height.takeIf { it > 0 }?.let { "${it}p" }, part.container.uppercase().takeIf { it.isNotBlank() },
    part.videoCodec.uppercase().takeIf { it.isNotBlank() }, part.audioCodec.uppercase().takeIf { it.isNotBlank() },
    part.sizeBytes.takeIf { it > 0 }?.let {
        if (it >= 1024L * 1024 * 1024) String.format(Locale.ROOT, "%.1f GB", it / (1024.0 * 1024 * 1024))
        else String.format(Locale.ROOT, "%.0f MB", it / (1024.0 * 1024))
    },
).joinToString(" · ").ifBlank { "原文件" }

@Composable
fun PlexVersionPicker(movie: PlexMovie, initial: PlexPart?, onDismiss: () -> Unit, onPlay: (PlexPart) -> Unit) {
    var selected by remember(movie.ratingKey) { mutableStateOf(initial?.key ?: movie.parts.firstOrNull()?.key) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择播放版本") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(movie.title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            movie.parts.forEachIndexed { index, part ->
                Row(Modifier.fillMaxWidth().clickable { selected = part.key }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == part.key, onClick = { selected = part.key })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("版本 ${index + 1} · ${plexVersionDescription(part)}", style = MaterialTheme.typography.bodyMedium)
                        part.filePath.substringAfterLast('/').takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { movie.parts.firstOrNull { it.key == selected }?.let(onPlay) }) { Text("播放此版本") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
