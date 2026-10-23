package com.megatron.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Lucide glyphs share a touch frame without scaling the drawing to the whole button. */
@Composable
fun PlayerGlyph(name: String, description: String? = null, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val resource = when (name) {
        "arrow-left" -> R.drawable.player_v2_arrow_left
        "play" -> R.drawable.player_v2_play
        "pause" -> R.drawable.player_v2_pause
        "rotate-ccw" -> R.drawable.player_v2_rotate_ccw
        "rotate-cw" -> R.drawable.player_v2_rotate_cw
        "skip-back" -> R.drawable.player_v2_skip_back
        "skip-forward" -> R.drawable.player_v2_skip_forward
        "lock-keyhole" -> R.drawable.player_v2_lock_keyhole
        "lock-keyhole-open" -> R.drawable.player_v2_lock_keyhole_open
        "rotate-3d" -> R.drawable.player_v2_rotate_3d
        "ellipsis" -> R.drawable.player_v2_ellipsis
        "audio-lines" -> R.drawable.player_v2_audio_lines
        "captions" -> R.drawable.player_v2_captions
        "gauge" -> R.drawable.player_v2_gauge
        "list-video" -> R.drawable.player_v2_list_video
        "sliders-horizontal" -> R.drawable.player_v2_sliders_horizontal
        "x" -> R.drawable.player_v2_x
        "check" -> R.drawable.player_v2_check
        "plus" -> R.drawable.player_v2_plus
        "minus" -> R.drawable.player_v2_minus
        "clock-3" -> R.drawable.player_v2_clock_3
        "info" -> R.drawable.player_v2_info
        "copy" -> R.drawable.player_v2_copy
        "folder-open" -> R.drawable.player_v2_folder_open
        "chevron-right" -> R.drawable.player_v2_chevron_right
        "chevron-left" -> R.drawable.player_v2_chevron_left
        "volume-2" -> R.drawable.player_v2_volume_2
        "wifi-off" -> R.drawable.player_v2_wifi_off
        "loader-circle" -> R.drawable.player_v2_loader_circle
        "maximize" -> R.drawable.player_v2_maximize
        "rectangle-horizontal" -> R.drawable.player_v2_rectangle_horizontal
        "monitor" -> R.drawable.player_v2_monitor
        "circle-help" -> R.drawable.player_v2_circle_help
        "refresh-cw" -> R.drawable.player_v2_refresh_cw
        "chevron-down" -> R.drawable.player_v2_chevron_down
        else -> R.drawable.player_v2_info
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = if (maxWidth <= 34.dp && maxWidth > 0.dp) maxWidth else 24.dp
        Icon(painterResource(resource), description, Modifier.size(size), tint = tint)
    }
}
