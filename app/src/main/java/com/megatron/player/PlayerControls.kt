package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

enum class PlayerAction { BACK, PLAY_PAUSE, ROTATION_LOCK, TOUCH_LOCK, MORE, JUMP, FULLSCREEN, AUDIO, SUBTITLES, SPEED, EPISODES, SYNC }

@Composable
fun PlayerControls(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    canSeek: Boolean,
    rotationLocked: Boolean,
    fullscreen: Boolean,
    hasEpisodes: Boolean,
    playbackSpeed: Float,
    audioOffsetMs: Int,
    showCenterPlay: Boolean = true,
    onAction: (PlayerAction) -> Unit,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val safePosition = positionMs.coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    var scrubPosition by remember(durationMs) { mutableLongStateOf(safePosition) }
    var scrubbing by remember { mutableStateOf(false) }
    val shownPosition = if (scrubbing) scrubPosition else safePosition
    val fraction = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val active = Color(0xFFE6BF7A)
    val text = Color(0xFFF3F1EB)
    val muted = Color(0xFFADAFAE)
    val iconButton = Modifier.size(48.dp).semantics { role = Role.Button }
    val interaction = remember { MutableInteractionSource() }
    val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val showRemaining = LocalConfiguration.current.screenWidthDp >= 360

    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color(0xEE000000))))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            PlayerGlyph("arrow-left", "返回", iconButton.clickable(interactionSource = interaction, indication = null) { onAction(PlayerAction.BACK) })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                val parts = if (hasEpisodes) title.split(" · ", limit = 2) else listOf(title)
                Text(parts.first(), color = text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (parts.size > 1) Text(parts[1], color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerGlyph("rotate-3d", if (rotationLocked) "旋转已锁定" else "旋转锁定", iconButton.clickable(interactionSource = interaction, indication = null) { onAction(PlayerAction.ROTATION_LOCK) }, tint = if (rotationLocked) active else text)
            PlayerGlyph("lock-keyhole", "触摸锁定", iconButton.clickable(interactionSource = interaction, indication = null) { onAction(PlayerAction.TOUCH_LOCK) })
            PlayerGlyph("ellipsis", "更多", iconButton.clickable(interactionSource = interaction, indication = null) { onAction(PlayerAction.MORE) })
        }

        if (showCenterPlay) {
            Box(Modifier.align(Alignment.Center).offset(y = -(maxHeight * .09f)).size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))
                .clickable(interactionSource = interaction, indication = null) { onAction(PlayerAction.PLAY_PAUSE); onInteraction() }
                .semantics { role = Role.Button; contentDescription = if (isPlaying) "暂停" else "播放" }, contentAlignment = Alignment.Center) {
                PlayerGlyph(if (isPlaying) "pause" else "play", if (isPlaying) "暂停" else "播放", Modifier.size(34.dp), tint = text)
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = canSeek, onClick = { onAction(PlayerAction.JUMP); onInteraction() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    PlayerGlyph("clock-3", "跳转到指定时间", Modifier.size(17.dp), tint = muted)
                    Spacer(Modifier.width(6.dp)); Text("${formatPlayerTime(shownPosition)} / ${formatPlayerTime(duration)}", color = text)
                }
                Spacer(Modifier.weight(1f))
                if (showRemaining) Text("剩余 ${formatPlayerTime((duration - shownPosition).coerceAtLeast(0L))}", color = muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                PlayerGlyph("maximize", if (rotationLocked) "方向已锁定，解锁后切换横竖屏" else "切换横竖屏", iconButton.clickable(enabled = !rotationLocked, interactionSource = interaction, indication = null) { onAction(PlayerAction.FULLSCREEN); onInteraction() }, tint = if (rotationLocked) muted else text)
            }
            Slider(
                value = fraction,
                onValueChange = { value ->
                    if (canSeek && duration > 0) {
                        scrubbing = true
                        scrubPosition = (duration * value).toLong().coerceIn(0L, duration)
                        onScrub(scrubPosition)
                        onInteraction()
                    }
                },
                onValueChangeFinished = {
                    if (scrubbing) onSeek(scrubPosition)
                    scrubbing = false
                    onScrub(null)
                    onInteraction()
                },
                enabled = canSeek && duration > 0,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "播放进度 ${formatPlayerTime(shownPosition)} / ${formatPlayerTime(duration)}" },
                colors = SliderDefaults.colors(thumbColor = active, activeTrackColor = active, inactiveTrackColor = Color.White.copy(alpha = .28f), disabledThumbColor = muted, disabledActiveTrackColor = muted),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (portrait) Arrangement.SpaceEvenly else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                PlayerTool("audio-lines", "音轨", onAction, PlayerAction.AUDIO, onInteraction, vertical = portrait, modifier = if (portrait) Modifier.weight(1f) else Modifier)
                PlayerTool("captions", "字幕", onAction, PlayerAction.SUBTITLES, onInteraction, vertical = portrait, modifier = if (portrait) Modifier.weight(1f) else Modifier)
                PlayerTool("gauge", if (playbackSpeed == 1f) "倍速" else "${playbackSpeed}×", onAction, PlayerAction.SPEED, onInteraction, vertical = portrait, modifier = if (portrait) Modifier.weight(1f) else Modifier)
                if (hasEpisodes) PlayerTool("list-video", "选集", onAction, PlayerAction.EPISODES, onInteraction, vertical = portrait, modifier = if (portrait) Modifier.weight(1f) else Modifier)
                PlayerTool("sliders-horizontal", "音画同步", onAction, PlayerAction.SYNC, onInteraction, badge = audioOffsetMs != 0, vertical = portrait, modifier = if (portrait) Modifier.weight(1f) else Modifier)
            }
        }
    }
}

@Composable
private fun PlayerTool(icon: String, label: String, onAction: (PlayerAction) -> Unit, action: PlayerAction, onInteraction: () -> Unit, badge: Boolean = false, vertical: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable { onAction(action); onInteraction() }
        .semantics { role = Role.Button; contentDescription = label }, contentAlignment = Alignment.Center) {
        if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
            PlayerGlyph(icon, label, Modifier.size(21.dp))
            Text(label, color = Color(0xFFF3F1EB), style = MaterialTheme.typography.labelSmall)
            if (badge) Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFE6BF7A)))
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            PlayerGlyph(icon, label, Modifier.size(21.dp))
            Text(label, color = Color(0xFFF3F1EB), style = MaterialTheme.typography.labelSmall)
            if (badge) Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFE6BF7A)))
        }
    }
}

private fun formatPlayerTime(valueMs: Long): String {
    val total = valueMs.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    return if (hours > 0) "%d:%02d:%02d".format(hours, (total / 60L) % 60L, total % 60L)
    else "%02d:%02d".format(total / 60L, total % 60L)
}
