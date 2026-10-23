package com.megatron.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Shared Android list indicator. It stays visible when the list can scroll. */
@Composable
fun MusicListScrollbar(state: LazyListState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = .65f)
    Box(modifier) {
        content()
        val info = state.layoutInfo
        if (info.totalItemsCount > 0 && (state.canScrollBackward || state.canScrollForward)) {
            val first = info.visibleItemsInfo.firstOrNull()
            val progress = ((state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / (first?.size ?: 1).coerceAtLeast(1)) /
                info.totalItemsCount).coerceIn(0f, 1f)
            Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp).padding(vertical = 5.dp).semantics {
                contentDescription = "列表滚动条"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }) {
                val fraction = info.visibleItemsInfo.sumOf { item ->
                    (minOf(item.offset + item.size, info.viewportEndOffset) - maxOf(item.offset, info.viewportStartOffset)).coerceAtLeast(0).toDouble() / item.size.coerceAtLeast(1)
                }.toFloat() / info.totalItemsCount
                val thumb = (size.height * fraction).coerceIn(minOf(28.dp.toPx(), size.height), size.height)
                val end = (1f - fraction).coerceAtLeast(.001f)
                val y = (size.height - thumb) * (progress / end).coerceIn(0f, 1f)
                drawRoundRect(color, Offset(size.width - 3.dp.toPx(), y), Size(3.dp.toPx(), thumb), CornerRadius(2.dp.toPx()))
            }
        }
    }
}
