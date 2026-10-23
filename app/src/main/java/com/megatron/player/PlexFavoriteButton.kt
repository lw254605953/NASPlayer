package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.megatron.player.plex.*

@Composable
fun PlexFavoriteButton(config: PlexServerConfig, movie: PlexMovie) {
    val context = LocalContext.current
    val store = remember { PlexFavoritesStore(context) }
    var selected by remember(config.id, config.machineIdentifier, movie.ratingKey) { mutableStateOf(false) }
    var failure by remember(config.id, movie.ratingKey) { mutableStateOf<String?>(null) }
    var loaded by remember(config.id, movie.ratingKey) { mutableStateOf(false) }
    LaunchedEffect(config.id, config.machineIdentifier, movie.ratingKey) {
        try { selected = store.contains(config, movie.ratingKey); loaded = true }
        catch (_: Exception) { failure = "收藏无法读取，请重开详情页后重试。" }
    }
    OutlinedButton(onClick = {
        try { selected = store.toggle(config, movie); failure = null }
        catch (_: Exception) { failure = "收藏未能保存，请重试。" }
    }, enabled = loaded, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
        Icon(if (selected) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
        Spacer(Modifier.width(8.dp))
        Text(if (selected) "已收藏" else "收藏")
    }
    failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
