package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*

@Composable
internal fun MusicLyricsPanel(lyrics: SongLyrics, positionMs: Long, modifier: Modifier = Modifier) {
    val scroll = rememberLazyListState()
    val active = lyrics.activeLine(positionMs)
    LaunchedEffect(lyrics, active) {
        if (active >= 0) scroll.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    MusicListScrollbar(scroll, modifier.fillMaxSize()) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(lyrics.lines) { index, line ->
                Text(line.text.ifEmpty { " " }, Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics { selected = index == active }, textAlign = TextAlign.Center,
                    fontSize = if (index == active) 17.sp else 14.sp, lineHeight = 24.sp,
                    fontWeight = if (index == active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!lyrics.timed || index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
            }
        }
    }
}
