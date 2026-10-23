package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.plexEpisodeLabel
import kotlin.math.roundToInt

enum class PlayerPanel(val title: String) {
    AUDIO("音轨"), SUBTITLES("字幕"), SPEED("播放速度"), EPISODES("选集"), SYNC("音画同步"),
    CAST("投屏到电视"), BOOKMARKS("视频时间点"), MORE("更多"), INFO("播放信息"), JUMP("跳转到指定时间"), PREFERENCES("播放偏好"), SLEEP_TIMER("定时关闭")
}

private val PanelText = Color(0xFFF3F1EB)
private val PanelMuted = Color(0xFFADAFAE)
private val PanelGold = Color(0xFFE6BF7A)

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun PlayerPanelContent(
    panel: PlayerPanel,
    session: PlayerSession,
    revision: Int,
    positionMs: Long,
    durationMs: Long,
    subtitleName: String?,
    hasExternalSubtitle: Boolean,
    episodes: List<PlexMovie>,
    currentEpisodeKey: String?,
    episodesLoading: Boolean,
    episodeSwitchBusy: Boolean,
    previousEpisodeTitle: String?,
    nextEpisodeTitle: String?,
    autoPlayNext: Boolean,
    resizeMode: Int,
    onChanged: () -> Unit,
    onPanelChange: (PlayerPanel) -> Unit,
    onDismiss: () -> Unit,
    onChooseSubtitle: () -> Unit,
    onRemoveSubtitle: () -> Unit,
    onCopy: () -> Unit,
    onSelectEpisode: (String) -> Unit,
    onRetryEpisodes: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onResizeModeChange: (Int) -> Unit,
    videoFavorite: Boolean,
    favoriteBusy: Boolean,
    onToggleVideoFavorite: () -> Unit,
    onCaptureTimePoint: () -> Unit,
    onSeriesSkipSettings: (() -> Unit)? = null,
) {
    // Media3 state is not Compose state. Capture it against the explicit event revision.
    val audioOffset = remember(session, revision) { session.audioOffsetMs }
    val rememberedOffset = remember(session, revision) { session.audioSyncRemembered }
    val audioTrackLabel = remember(session, revision) { session.audioSyncTrackLabel() }
    val subtitleOffset = remember(session, revision) { session.subtitleOffsetMs }
    when (panel) {
        PlayerPanel.AUDIO, PlayerPanel.SUBTITLES -> {
            val type = if (panel == PlayerPanel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            val disabled = session.player.trackSelectionParameters.disabledTrackTypes.contains(type)
            if (type == C.TRACK_TYPE_TEXT) PanelChoice("关闭字幕", selected = disabled, onClick = {
                session.trackSelector.setParameters(session.trackSelector.parameters.buildUpon().setTrackTypeDisabled(type, true))
                onChanged()
            })
            val groups = session.player.currentTracks.groups.filter { it.type == type }
            var number = 0
            if (groups.isEmpty()) PanelNote(if (type == C.TRACK_TYPE_AUDIO) "无音轨" else "无字幕")
            groups.forEach { group ->
                repeat(group.length) { index ->
                    val format = group.getTrackFormat(index)
                    val order = ++number
                    PanelChoice(trackLabel(format, order, type), trackDetails(format), selected = !disabled && group.isTrackSelected(index),
                        enabled = group.isTrackSupported(index), onClick = {
                            session.trackSelector.setParameters(session.trackSelector.parameters.buildUpon()
                                .setTrackTypeDisabled(type, false).clearOverridesOfType(type)
                                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)))
                            onChanged()
                        })
                }
            }
            if (type == C.TRACK_TYPE_TEXT) {
                HorizontalDivider(color = Color.White.copy(alpha = .1f))
                if (session.subtitleDiscoveryStatus != null) {
                    PanelNote("NAS 同名字幕")
                    session.subtitleDiscoveryStatus?.let { PanelNote(it) }
                    session.nasSubtitles.forEach { subtitle ->
                        PanelChoice(subtitle.name, null, selected = session.isExternalSubtitleSelected(subtitle.uri), onClick = {
                            session.replaceSubtitle(subtitle.uri, subtitle.name); onChanged()
                        })
                    }
                    TextButton(onClick = { session.discoverNasSubtitles(force = true); onChanged() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新查找同名字幕") }
                }
                OutlinedButton(onClick = onChooseSubtitle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    PlayerGlyph("folder-open", null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("选择外挂字幕")
                }
                if (hasExternalSubtitle) {
                    PanelNote(subtitleName ?: "已选择外挂字幕")
                    TextButton(onClick = onRemoveSubtitle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("移除外挂字幕") }
                }
                val store = rememberPlaybackPreferencesStore()
                PanelNote("字幕字号")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SubtitleSize.entries.forEach { size ->
                        FilterChip(selected = store.read().subtitleSize == size, onClick = {
                            store.saveSubtitleSize(size); session.applyPlaybackPreferences(); onChanged()
                        }, label = { Text(size.label, maxLines = 1) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .1f))
                SubtitleTimingPanel(session, subtitleOffset, onChanged)
            }
        }
        PlayerPanel.SPEED -> {
            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                PanelChoice("${speed}×", if (speed == 1f) "正常速度" else null,
                    selected = session.player.playbackParameters.speed == speed, onClick = {
                        session.player.setPlaybackSpeed(speed); onChanged()
                    })
            }
        }
        PlayerPanel.SYNC -> AudioSyncPanel(session, audioOffset, rememberedOffset, audioTrackLabel, session.player.audioFormat != null && session.canSeek(), onChanged)
        PlayerPanel.EPISODES -> EpisodePanel(episodes, currentEpisodeKey, episodesLoading, episodeSwitchBusy,
            positionMs, durationMs, previousEpisodeTitle, nextEpisodeTitle, autoPlayNext,
            onSelectEpisode, onRetryEpisodes, onPreviousEpisode, onNextEpisode, onAutoPlayNextChange)
        PlayerPanel.CAST, PlayerPanel.BOOKMARKS -> Unit // Rendered by PlayerScreen with the current video's saved points.
        PlayerPanel.MORE -> {
            PanelChoice("投屏到电视", "Google Cast / DLNA", icon = "monitor", onClick = { onPanelChange(PlayerPanel.CAST) })
            PanelChoice(if (videoFavorite) "取消视频收藏" else "收藏视频", icon = if (videoFavorite) "check" else "plus", enabled = !favoriteBusy, onClick = onToggleVideoFavorite)
            PanelChoice("收藏当前时间点", null, icon = "clock-3", enabled = session.canSeek(), onClick = onCaptureTimePoint)
            PanelChoice("本视频的时间点", null, icon = "list-video", onClick = { onPanelChange(PlayerPanel.BOOKMARKS) })
            PanelChoice("跳转到时间", formatPlaybackTime(positionMs), icon = "clock-3", enabled = session.canSeek(), onClick = { onPanelChange(PlayerPanel.JUMP) })
            PanelChoice("定时关闭", null, icon = "clock-3", onClick = { onPanelChange(PlayerPanel.SLEEP_TIMER) })
            PanelChoice("播放信息", null, icon = "info", onClick = { onPanelChange(PlayerPanel.INFO) })
            if (onSeriesSkipSettings != null) PanelChoice("本剧片头片尾", null, icon = "sliders-horizontal", onClick = onSeriesSkipSettings)
            PanelChoice("播放偏好", null, icon = "sliders-horizontal", onClick = { onPanelChange(PlayerPanel.PREFERENCES) })
            HorizontalDivider(color = Color.White.copy(alpha = .1f))
            PanelNote("画面比例")
            listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应屏幕", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "填满屏幕").forEach { (mode, label) ->
                PanelChoice(label,
                    selected = resizeMode == mode, onClick = { onResizeModeChange(mode) })
            }
        }
        PlayerPanel.INFO -> {
            session.audioNotice()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(session.diagnostics(), color = PanelText, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                PlayerGlyph("copy", null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("复制播放信息")
            }
        }
        PlayerPanel.JUMP -> JumpTimePanel(positionMs, durationMs, onDismiss) { target ->
            if (session.seekToPosition(target)) { onChanged(); onDismiss() }
        }
        PlayerPanel.SLEEP_TIMER -> SleepTimerContent(onDismiss)
        PlayerPanel.PREFERENCES -> PlaybackPreferencesPanel { session.applyPlaybackPreferences(); onChanged() }
    }
}

@Composable
private fun SubtitleTimingPanel(session: PlayerSession, offset: Int, onChanged: () -> Unit) {
    PanelNote("字幕时间调整 · ${subtitleOffsetText(offset)}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { session.setSubtitleOffsetMs(offset - 500); onChanged() }, enabled = offset > -10_000,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("提前 0.5 秒") }
        OutlinedButton(onClick = { session.setSubtitleOffsetMs(offset + 500); onChanged() }, enabled = offset < 10_000,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("延后 0.5 秒") }
    }
    TextButton(onClick = { session.setSubtitleOffsetMs(0); onChanged() }, enabled = offset != 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复字幕时间") }
}

@Composable
private fun rememberPlaybackPreferencesStore(): PlaybackPreferencesStore {
    val context = LocalContext.current
    return remember(context) { PlaybackPreferencesStore(context) }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun AudioSyncPanel(session: PlayerSession, offset: Int, remembered: Boolean, trackLabel: String, enabled: Boolean, onChanged: () -> Unit) {
    var step by remember { mutableIntStateOf(50) }
    var slider by remember(offset) { mutableFloatStateOf(offset.toFloat()) }
    PanelNote(trackLabel)
    Text(audioSyncOffsetText(slider.roundToInt()), Modifier.fillMaxWidth().padding(top = 10.dp),
        color = PanelGold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
    Slider(value = slider, valueRange = -2000f..2000f, steps = 79, enabled = enabled,
        onValueChange = { slider = (it / 50).roundToInt() * 50f },
        onValueChangeFinished = { session.setAudioOffsetMs(slider.roundToInt()); onChanged() },
        modifier = Modifier.fillMaxWidth().height(48.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("提前2秒", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
        Text("0", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
        Text("延后2秒", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncDirectionButton("声音早了", "将声音延后${step}毫秒", enabled && offset < 2000, Modifier.weight(1f)) {
            session.setAudioOffsetMs(offset + step); onChanged()
        }
        SyncDirectionButton("声音晚了", "将声音提前${step}毫秒", enabled && offset > -2000, Modifier.weight(1f)) {
            session.setAudioOffsetMs(offset - step); onChanged()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("每次调整", Modifier.weight(1f), color = PanelMuted, style = MaterialTheme.typography.bodySmall)
        listOf(50, 200).forEach { value -> FilterChip(selected = step == value, onClick = { step = value }, label = { Text("$value ms") }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
    OutlinedButton(enabled = enabled && offset != 0, onClick = { session.setAudioOffsetMs(0); onChanged() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复同步") }
    HorizontalDivider(color = Color.White.copy(alpha = .1f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("记住此视频的调整", Modifier.weight(1f), color = PanelText, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = remembered, enabled = enabled, onCheckedChange = { session.setAudioSyncRemembered(it); onChanged() })
    }
}

@Composable
private fun SyncDirectionButton(title: String, detail: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 76.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .04f)) {
        Column(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = if (enabled) PanelText else PanelMuted, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = PanelMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EpisodePanel(
    episodes: List<PlexMovie>, currentKey: String?, loading: Boolean, busy: Boolean,
    positionMs: Long, durationMs: Long, previous: String?, next: String?, autoNext: Boolean,
    onSelect: (String) -> Unit, onRetry: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onAutoNext: (Boolean) -> Unit,
) {
    val seasons = episodes.map { it.parentIndex }.distinct().sorted()
    val currentSeason = episodes.firstOrNull { it.ratingKey == currentKey }?.parentIndex
    var season by remember(currentKey, seasons) { mutableIntStateOf(currentSeason ?: seasons.firstOrNull() ?: -1) }
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, enabled = seasons.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(seasonLabel(season), Modifier.weight(1f)); PlayerGlyph("chevron-down", null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            seasons.forEach { value -> DropdownMenuItem(text = { Text(seasonLabel(value)) }, onClick = { season = value; menu = false }) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("自动播放下一集", Modifier.weight(1f), color = PanelText)
        Switch(checked = autoNext, onCheckedChange = onAutoNext)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPrevious, enabled = !busy && previous != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一集") }
        OutlinedButton(onClick = onNext, enabled = !busy && next != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下一集") }
    }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); PanelNote("正在打开剧集…") }
    if (loading) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); PanelNote("正在读取本剧集列表…") }
    else if (episodes.isEmpty()) {
        PanelNote("剧集列表加载失败")
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新读取剧集") }
    }
    episodes.filter { it.parentIndex == season }.forEach { episode ->
        val current = episode.ratingKey == currentKey
        val position = if (current) positionMs else episode.viewOffsetMs
        val duration = if (current && durationMs > 0) durationMs else episode.durationMs
        val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Surface(Modifier.fillMaxWidth(), color = if (current) PanelGold.copy(alpha = .07f) else Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.clickable(enabled = !busy && !current, role = Role.Button) { onSelect(episode.ratingKey) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (episode.index >= 0) episode.index.toString().padStart(2, '0') else "·", color = PanelGold, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${plexEpisodeLabel(episode, includeSeason = false)} · ${episode.title}", color = PanelText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (current) "正在播放 · ${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}" else if (duration > 0) "${duration / 60_000} 分钟" else "时长未知", color = PanelMuted, style = MaterialTheme.typography.labelSmall)
                    if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = PanelGold, trackColor = Color.White.copy(alpha = .12f))
                }
                if (current || episode.watched) PlayerGlyph(if (current) "audio-lines" else "check", if (current) "当前集" else "已看", Modifier.size(19.dp), tint = PanelGold)
            }
        }
    }
}

private fun seasonLabel(index: Int): String = when { index == 0 -> "特别篇"; index > 0 -> "第${index}季"; else -> "季信息未知" }

@Composable
private fun JumpTimePanel(positionMs: Long, durationMs: Long, onDismiss: () -> Unit, onJump: (Long) -> Unit) {
    var input by remember { mutableStateOf(formatPlaybackTime(positionMs)) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val target = parsePlaybackTimeMs(input.trim())
        error = when { target == null -> "请输入秒数、分:秒或时:分:秒"; durationMs <= 0 -> "当前视频暂不支持时间跳转"; target > durationMs -> "时间超出影片时长 ${formatPlaybackTime(durationMs)}"; else -> null }
        if (error == null && target != null) onJump(target)
    }
    PanelNote("当前 ${formatPlaybackTime(positionMs)} / 总时长 ${formatPlaybackTime(durationMs)}")
    OutlinedTextField(input, { input = it; error = null }, label = { Text("播放时间") }, singleLine = true, isError = error != null,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
        Button(onClick = ::submit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("跳转") }
    }
}

@Composable
private fun PanelChoice(title: String, detail: String? = null, selected: Boolean = false, enabled: Boolean = true, icon: String? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = if (selected) PanelGold.copy(alpha = .07f) else Color.Transparent, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) PlayerGlyph(icon, null, Modifier.size(20.dp), PanelGold)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = if (enabled) PanelText else PanelMuted, style = MaterialTheme.typography.bodyMedium)
                if (!detail.isNullOrBlank()) Text(detail, color = PanelMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (selected) PlayerGlyph("check", "已选择", Modifier.size(19.dp), PanelGold)
        }
    }
}

@Composable
private fun PanelNote(text: String) { Text(text, color = PanelMuted, style = MaterialTheme.typography.bodySmall) }

private fun trackLabel(format: Format, index: Int, type: Int): String {
    format.label?.takeIf { it.isNotBlank() }?.let { return it }
    val code = format.language.orEmpty().substringBefore('-').lowercase()
    return when (code) { "zh", "zho", "chi" -> "中文"; "en", "eng" -> "英语"; "ja", "jpn" -> "日语"; "ko", "kor" -> "韩语"; "fr", "fra", "fre" -> "法语"; "de", "deu", "ger" -> "德语"; "" , "und" -> if (type == C.TRACK_TYPE_AUDIO) "音轨 $index" else "字幕 $index"; else -> java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE).ifBlank { code } }
}
private fun trackDetails(format: Format): String = listOfNotNull(
    format.sampleMimeType?.substringAfter('/'), format.channelCount.takeIf { it > 0 }?.let { "$it 声道" }, format.sampleRate.takeIf { it > 0 }?.let { "${it / 1000f} kHz" }
).joinToString(" · ")
