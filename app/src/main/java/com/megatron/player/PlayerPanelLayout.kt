package com.megatron.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** A custom in-player panel: right sheet on wide landscape, bottom sheet otherwise. */
@Composable
fun PlayerPanelLayout(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val wide = landscape && configuration.screenWidthDp >= 600
    val scrimInteraction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss))
        Column(
            Modifier
                .then(if (wide) Modifier.widthIn(max = 360.dp).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(.86f))
                .clip(if (wide) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color(0xF2222527))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .then(if (wide) Modifier.align(Alignment.CenterEnd) else Modifier.align(Alignment.BottomCenter)),
        ) {
            if (!wide) Box(Modifier.padding(top = 8.dp).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .28f)).align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    PlayerGlyph("chevron-left", "返回", Modifier.size(48.dp).clickable { onBack() })
                }
                Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color(0xFFF3F1EB), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                PlayerGlyph("x", "关闭面板", Modifier.size(48.dp).clickable { onDismiss() })
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
