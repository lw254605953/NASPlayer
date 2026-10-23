package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.megatron.player.music.metadata.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicOrganizePanel(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { MusicOrganizeEngine.shared(context) }
    val state by engine.state.collectAsState()
    val store = remember { MusicSupplementStore(context) }
    var consent by remember { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var fields by rememberSaveable { mutableStateOf(SupplementFields()) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<OrganizeRow?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    var excluded by rememberSaveable(state.rows.map { it.track.id }) { mutableStateOf(emptySet<String>()) }
    val selected = state.rows.filter { it.choice != null && it.status != "已保存" && it.track.id !in excluded }.map { it.track.id }.toSet()
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { BetaFeatureLabel("整理音乐资料", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭资料整理") } }
            if (state.rows.isEmpty()) Text("暂无待整理歌曲")
            if (state.remaining > 0) Text("本次 ${state.rows.size} 首 · 其余 ${state.remaining} 首可在本次保存后继续整理", style = MaterialTheme.typography.bodySmall)
            Text("所选歌名线索将发送至 Qwen；匹配后查询 MusicBrainz、封面和歌词服务。不发送音频、目录或账号凭据。请先检查下方查询文字。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { Checkbox(consent, { consent = it }, enabled = !state.running); Text("允许本次联网匹配", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { engine.start(state.rows.filterNot { it.track.id in excluded }.map { it.track.id }.toSet()) }, enabled = consent && !state.running && state.rows.isNotEmpty()) { BetaFeatureLabel("开始匹配") }
                if (state.running) OutlinedButton(onClick = engine::pause) { Text("暂停") }
                else TextButton(onClick = onSettings) { BetaFeatureLabel("模型设置") }
            }
            if (state.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${state.finished}/${state.rows.size}", style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.rows.forEach { row ->
                ChannelGroup {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.track.title, style = MaterialTheme.typography.titleSmall)
                        Text(row.clue.ifBlank { "待识别" }, style = MaterialTheme.typography.bodySmall)
                        Text(row.status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        if (row.status != "已保存") Row { Checkbox(row.track.id !in excluded, { checked -> excluded = if (checked) excluded - row.track.id else excluded + row.track.id }, enabled = !state.running); Text("包含此曲", Modifier.padding(top = 12.dp)) }
                        row.choice?.let { match ->
                            Text("${match.title}\n${match.artist}\n${match.album}", style = MaterialTheme.typography.bodySmall)
                            Text(match.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { editId = row.track.id; editText = row.clue; editError = null }, enabled = !state.running) { Text("编辑查询") }
                            if (row.matches.isNotEmpty()) TextButton(onClick = { detail = row }, enabled = !state.running) { Text("候选与差异") }
                            if (row.status == "已保存" && row.choice != null) TextButton(onClick = { engine.select(row.track.id, row.choice!!.id) }, enabled = !state.running) { Text("补齐缺失项") }
                            if (store.has(row.track.id)) TextButton(onClick = { engine.undo(row.track.id) }, enabled = !state.running) { Text("撤销补全") }
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                ChannelLabel("保存字段")
                listOf("歌名" to fields.title, "歌手" to fields.artist, "专辑" to fields.album, "封面" to fields.artwork, "歌词" to fields.lyrics).forEachIndexed { i, (label, checked) ->
                    Row { Checkbox(checked, { value -> fields = when (i) { 0 -> fields.copy(title = value); 1 -> fields.copy(artist = value); 2 -> fields.copy(album = value); 3 -> fields.copy(artwork = value); else -> fields.copy(lyrics = value) } }, enabled = !state.running); Text(label, Modifier.padding(top = 12.dp)) }
                }
                Row { Checkbox(replace, { replace = it }, enabled = !state.running); Text("替换已有资料", Modifier.padding(top = 12.dp)) }
                Button(onClick = { confirmSave = true }, enabled = !state.running && consent, modifier = Modifier.fillMaxWidth()) { Text("确认保存 ${selected.size} 首") }
            }
            Text("MusicBrainz · Cover Art Archive · LRCLIB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    detail?.let { original ->
        val row = state.rows.firstOrNull { it.track.id == original.track.id } ?: original
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("候选与差异") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前：${row.track.title}\n歌手：${row.track.artist.ifBlank { "未填写" }}\n专辑：${row.track.album.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodySmall)
                row.matches.forEach { match ->
                    Row { RadioButton(match.id == row.selected, onClick = { engine.select(row.track.id, match.id) })
                        Column(Modifier.weight(1f)) { Text("${match.title}\n${match.artist}\n${match.album}", style = MaterialTheme.typography.bodySmall)
                            Text(match.version, style = MaterialTheme.typography.labelSmall)
                            Text(match.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("完成") } })
    }
    editId?.let { id -> AlertDialog(onDismissRequest = { editId = null }, title = { Text("查询文字") }, text = {
        Column { OutlinedTextField(editText, { editText = it.take(240) }, label = { Text("歌名与歌手") }); editError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(onClick = { try { engine.editClue(id, editText); editId = null } catch (e: Exception) { editError = e.message ?: "查询文字无效" } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editId = null }) { Text("取消") } }) }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("保存 ${selected.size} 首歌曲资料？") }, text = {
        Text(if (replace) "替换所选字段的本机显示资料，原音频不变。封面、歌词按所选版本查询。" else "只补充所选空缺字段，原音频不变。封面、歌词按所选版本查询。")
    }, confirmButton = { TextButton(onClick = { confirmSave = false; engine.save(selected, fields, replace) }) { Text("保存") } }, dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("取消") } })
}
