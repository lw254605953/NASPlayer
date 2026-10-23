package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TimePointNameDialog(initialName: String, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, onValueChange = { name = it.take(160) }, label = { Text("名称") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun VideoTimePointsPanel(points: List<VideoTimePoint>, canSeek: Boolean, onCapture: () -> Unit,
                         onPlay: (VideoTimePoint) -> Unit, onRename: (VideoTimePoint) -> Unit, onDelete: (VideoTimePoint) -> Unit) {
    OutlinedButton(onClick = onCapture, enabled = canSeek, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("收藏当前时间点") }
    if (points.isEmpty()) Text("暂无时间点", style = MaterialTheme.typography.bodySmall)
    points.sortedBy { it.video.positionMs }.forEach { point ->
        ListItem(headlineContent = { Text(point.name) }, supportingContent = { Text(formatPlaybackTime(point.video.positionMs)) },
            leadingContent = { IconButton(onClick = { onPlay(point) }, enabled = canSeek) { Icon(Icons.Default.PlayArrow, "跳转到 ${point.name}") } },
            trailingContent = { Row {
                IconButton(onClick = { onRename(point) }) { Icon(Icons.Default.Edit, "重命名 ${point.name}") }
                IconButton(onClick = { onDelete(point) }) { Icon(Icons.Default.DeleteOutline, "删除 ${point.name}") }
            } }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}
