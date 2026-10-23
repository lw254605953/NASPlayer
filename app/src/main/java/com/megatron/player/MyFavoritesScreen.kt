package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megatron.player.music.*
import com.megatron.player.plex.*
import com.megatron.player.source.*

@Composable
fun MyFavoritesScreen(titleFavorites: List<PlexFavorite>, favorites: List<SavedLocation>, musicFavorites: List<MusicTrack>,
                      sources: List<MediaSourceConfig>, plexSession: PlexBrowseSession, state: MusicPlaybackState,
                      modifier: Modifier = Modifier, error: String?, onBack: () -> Unit, onRetry: () -> Unit,
                      onOpenTitle: (PlexFavorite) -> Unit, onRemoveTitle: (PlexFavorite) -> Unit,
                      onOpenFile: (SavedLocation) -> Unit, onRemoveFile: (SavedLocation) -> Unit,
                      onPlayMusic: (MusicTrack) -> Unit, onMusicMenu: (MusicTrack) -> Unit, onLibrary: () -> Unit,
                      timePoints: List<VideoTimePoint>, videoFiles: List<VideoFileFavorite>,
                      onOpenPoint: (VideoTimePoint) -> Unit, onRemovePoint: (VideoTimePoint) -> Unit,
                      onRenamePoint: (VideoTimePoint, String) -> Unit, onOpenVideo: (VideoFileFavorite) -> Unit,
                      onRemoveVideo: (VideoFileFavorite) -> Unit) {
    var renamePoint by remember { mutableStateOf<VideoTimePoint?>(null) }
    renamePoint?.let { point -> TimePointNameDialog(point.name, "重命名时间点", { renamePoint = null },
        { name -> onRenamePoint(point, name); renamePoint = null }) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回设置") }
                Text("我的收藏", fontSize = 21.sp)
            }
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp); TextButton(onClick = onRetry) { Text("重试") } } }
        if (titleFavorites.isEmpty() && favorites.isEmpty() && musicFavorites.isEmpty() && timePoints.isEmpty() && videoFiles.isEmpty() && error == null) item {
            Column(Modifier.padding(vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.FavoriteBorder, null, tint = MaterialTheme.colorScheme.primary)
                Text("还没有收藏")
                TextButton(onClick = onLibrary) { Text("去媒体库") }
            }
        }
        if (timePoints.isNotEmpty()) item { ChannelLabel("视频时间点") }
        items(timePoints.asReversed(), key = { "point:${it.id}" }) { point ->
            ChannelGroup {
                ChannelRow(point.name, "${point.video.title} · ${formatPlaybackTime(point.video.positionMs)}", Icons.Default.Bookmark,
                    onClick = { onOpenPoint(point) }, trailing = {
                        Row {
                            IconButton(onClick = { renamePoint = point }) { Icon(Icons.Default.Edit, "重命名 ${point.name}") }
                            IconButton(onClick = { onRemovePoint(point) }) { Icon(Icons.Default.DeleteOutline, "删除 ${point.name}") }
                        }
                    })
            }
        }
        if (videoFiles.isNotEmpty()) item { ChannelLabel("视频文件") }
        items(videoFiles.asReversed(), key = { "video:${it.plexMachine}:${it.video.uri}" }) { file ->
            ChannelGroup {
                ChannelRow(file.video.title, "视频收藏", Icons.Default.Movie, onClick = { onOpenVideo(file) },
                    trailing = { IconButton(onClick = { onRemoveVideo(file) }) { Icon(Icons.Default.Star, "取消收藏 ${file.video.title}", tint = MaterialTheme.colorScheme.primary) } })
            }
        }
        if (titleFavorites.isNotEmpty()) item { ChannelLabel("影片与剧集") }
        items(titleFavorites.asReversed(), key = { "title:${it.key}" }) { item ->
            PlexFavoriteRow(item, plexSession, onOpen = { onOpenTitle(item) }, onRemove = { onRemoveTitle(item) })
        }
        if (musicFavorites.isNotEmpty()) item { ChannelLabel("音乐") }
        items(musicFavorites, key = { "music:${it.id}" }) { track -> MusicTrackRow(track, state.current?.id == track.id, { onPlayMusic(track) }, { onMusicMenu(track) }) }
        if (favorites.isNotEmpty()) item { ChannelLabel("目录与文件") }
        items(favorites, key = { "file:${it.sourceId}:${it.path}" }) { item ->
            ChannelGroup {
                ChannelRow(item.name, (sources.firstOrNull { it.id == item.sourceId }?.name ?: "连接已移除") + if (item.isDirectory) " · 目录" else " · 视频文件",
                    if (item.isDirectory) Icons.Default.FolderOpen else Icons.Default.Movie, onClick = { onOpenFile(item) },
                    trailing = { IconButton(onClick = { onRemoveFile(item) }) { Icon(Icons.Default.Star, "取消收藏 ${item.name}", tint = MaterialTheme.colorScheme.primary) } })
            }
        }
    }
}
