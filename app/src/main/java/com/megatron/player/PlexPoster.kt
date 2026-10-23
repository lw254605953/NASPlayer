package com.megatron.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.megatron.player.plex.*
import kotlinx.coroutines.CancellationException

@Composable
fun PlexPoster(credentials: PlexCredentials, path: String, client: PlexClient, modifier: Modifier = Modifier, description: String? = null,
               placeholder: ImageVector = Icons.Default.Movie, contentScale: ContentScale = ContentScale.Crop, portrait: Boolean = false) {
    val context = LocalContext.current
    val key = remember(credentials.config.id, credentials.config.machineIdentifier, credentials.config.address, credentials.token, path, portrait) {
        "${PlexDiskCache.identityFor(credentials)}:$path" + if (portrait) "|portrait-v2" else ""
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val edge = PlexPosterSizing.edge(constraints.maxWidth, constraints.maxHeight).let { if (portrait) maxOf(512, it) else it }
        var bitmap by remember(key, edge) { mutableStateOf(PlexPosterCache.peek(key, edge)) }
        LaunchedEffect(key, edge) {
            if (bitmap != null || path.isBlank()) return@LaunchedEffect
            try {
                bitmap = PlexPosterCache.load(context, key, edge) { client.imageBytes(credentials, path, portrait) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing poster must not prevent browsing or playback. */ }
        }
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.matchParentSize(), contentScale = contentScale) }
            ?: Icon(placeholder, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
