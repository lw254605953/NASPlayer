package com.megatron.player.cast

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.PlexMovie
import com.megatron.player.plex.plexEpisodeLabel

@Composable
fun CastEpisodeList(episodes: List<PlexMovie>, currentKey: String?, loading: Boolean, busy: Boolean,
    onSelect: (String) -> Unit) {
    HorizontalDivider()
    Text("全部剧集 · ${episodes.size}集", style = MaterialTheme.typography.titleMedium)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (episodes.isEmpty() && !loading) Text("暂无剧集", style = MaterialTheme.typography.bodySmall)
    // Plain rows share the remote page's single scroll container, including across seasons.
    episodes.sortedWith(compareBy({ it.parentIndex }, { it.index })).groupBy { it.parentIndex }.forEach { (season, entries) ->
        Text(if (season == 0) "特别篇" else "第${season.takeIf { it > 0 } ?: 1}季", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { episode ->
                        val current = episode.ratingKey == currentKey
                        val colors = if (current) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.outlinedButtonColors()
                        OutlinedButton(onClick = { if (!current) onSelect(episode.ratingKey) }, enabled = !busy && !loading,
                            border = if (current) null else ButtonDefaults.outlinedButtonBorder(!busy && !loading),
                            colors = colors,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                .semantics { contentDescription = plexEpisodeLabel(episode) + if (current) "，当前集" else "" },
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("第${episode.index.takeIf { it > 0 } ?: "?"}集", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge)
                                if (current) Text("当前集", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
