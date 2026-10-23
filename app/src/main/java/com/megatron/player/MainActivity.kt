package com.megatron.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.megatron.player.source.MediaSourceConfig
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceStore
import com.megatron.player.source.SourceUris
import com.megatron.player.plex.*
import com.megatron.player.music.*
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

private data class PlexVersionRequest(val config: PlexServerConfig, val movie: PlexMovie)

private data class OpenRequest(val uri: Uri?, val sequence: Long)

private data class PlexEpisodePlayback(
    val serverId: String,
    val showKey: String,
    val episodes: List<PlexMovie>,
    val currentKey: String,
    val previous: PlexMovie? = null,
    val next: PlexMovie? = null,
    val loading: Boolean = true,
)

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private var openRequest by mutableStateOf(OpenRequest(null, 0L))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW) {
            openRequest = OpenRequest(intent.data, System.nanoTime())
        }
        com.megatron.player.backup.BackupManager.shared(applicationContext).onAppStart()
        setContent { PlayerApp(openRequest) }
    }

    override fun onResume() {
        super.onResume()
        SleepTimer.shared(this).foreground(this)
    }
    override fun onPause() {
        SleepTimer.shared(this).foreground(null)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) openRequest = OpenRequest(intent.data, System.nanoTime())
    }
}

@Composable
private fun PlayerApp(request: OpenRequest) {
    val context = LocalContext.current
    val plexPageMemory = remember { PlexLibraryPageMemory() }
    val musicUi = remember { MusicUiState() }
    val musicRepository = remember { MusicRepository.shared(context) }
    val musicPlayback = remember { MusicPlayback(context) }
    DisposableEffect(musicPlayback) { onDispose { musicPlayback.close() } }
    val historyStore = remember { PlaybackHistory(context) }
    val sourceStore = remember { SourceStore(context) }
    val sleepTimer = remember { SleepTimer.shared(context) }
    val castController = remember { com.megatron.player.cast.CastController.shared(context) }
    val castState by castController.state.collectAsState()
    val versionPreferences = remember { PlexVersionPreferences(context) }
    var versionRequest by remember { mutableStateOf<PlexVersionRequest?>(null) }
    val plexStore = remember { PlexStore(context) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val coordinator = PlexProgressSyncCoordinator(context)
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) coordinator.onForeground() }
        coordinator.start()
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); coordinator.close() }
    }
    val plexBrowseSession = remember { PlexBrowseSession(context, scope) }
    LaunchedEffect(plexBrowseSession) { plexBrowseSession.start() }
    val browserStateHolder = rememberSaveableStateHolder()
    var sourceRevision by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(historyStore.list()) }
    var playingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var playingTitle by rememberSaveable { mutableStateOf("视频") }
    var subtitleUri by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitleName by rememberSaveable { mutableStateOf<String?>(null) }
    var initialPosition by rememberSaveable { mutableStateOf(0L) }
    var sourceDuration by rememberSaveable { mutableStateOf(0L) }
    var exactPlaybackPosition by rememberSaveable { mutableStateOf(false) }
    var preferRemotePosition by rememberSaveable { mutableStateOf(false) }
    var subtitleWasPlaying by rememberSaveable { mutableStateOf(false) }
    var episodePlayback by remember { mutableStateOf<PlexEpisodePlayback?>(null) }
    var episodeRequestToken by remember { mutableStateOf(0L) }
    var episodeSwitchBusy by remember { mutableStateOf(false) }
    var episodeLoadFailure by remember { mutableStateOf<String?>(null) }
    val playbackPreferences = remember { context.getSharedPreferences("player_preferences", Context.MODE_PRIVATE) }
    var autoPlayNext by remember { mutableStateOf(playbackPreferences.getBoolean("auto_play_next", true)) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun loadEpisodeNeighbors(config: PlexServerConfig, movie: PlexMovie, expectedUri: String? = null) {
        if (!movie.type.equals("episode", ignoreCase = true)) {
            episodePlayback = null
            episodeLoadFailure = null
            return
        }
        val showKey = movie.grandparentRatingKey.takeIf { it.isNotBlank() }
            ?: run { message = "Plex 没有返回该剧的剧集编号"; episodePlayback = null; return }
        expectedUri?.let { castController.configureEpisode(it, movie, emptyList(), exactPlaybackPosition) }
        val token = episodeRequestToken + 1L
        episodeRequestToken = token
        episodePlayback = PlexEpisodePlayback(config.id, showKey, emptyList(), movie.ratingKey)
        episodeLoadFailure = null
        scope.launch {
            try {
                val credentials = plexStore.credentials(config.id) ?: error("Plex 连接已移除")
                val episodes = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).allEpisodes(credentials, showKey) }
                if (token != episodeRequestToken || (expectedUri != null && playingUri != expectedUri)) return@launch
                val current = episodes.firstOrNull { it.ratingKey == movie.ratingKey } ?: movie
                if (!current.type.equals("episode", ignoreCase = true) || (current.grandparentRatingKey.isNotBlank() && current.grandparentRatingKey != showKey)) error("Plex 返回的剧集不属于当前剧集")
                episodePlayback = PlexEpisodePlayback(
                    config.id, showKey, episodes, current.ratingKey,
                    adjacentPlexEpisode(episodes, current.ratingKey, -1),
                    adjacentPlexEpisode(episodes, current.ratingKey, 1),
                    loading = false,
                )
                expectedUri?.let { castController.configureEpisode(it, current, episodes, exactPlaybackPosition) }
                episodeLoadFailure = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == episodeRequestToken && (expectedUri == null || playingUri == expectedUri)) {
                    episodePlayback = episodePlayback?.copy(loading = false)
                    episodeLoadFailure = plexErrorMessage(error)
                    message = "剧集列表加载失败：${plexErrorMessage(error)}"
                }
            }
        }
    }

    fun open(record: RecentVideo, remotePosition: Boolean = false, exactPosition: Boolean = false) {
        MusicPlaybackGate.pauseForVideo()
        val source = SourceUris.parse(Uri.parse(record.uri))
        if (source != null && sourceStore.find(source.first) == null) {
            message = "这个片源已移除，请重新添加后播放。"
            return
        }
        val plex = PlexUris.parse(Uri.parse(record.uri))
        if (plex != null && runCatching { plexStore.config()?.id }.getOrNull() != plex.serverId) {
            message = "这个 Plex 连接已移除，请到媒体库重新连接。"
            return
        }
        if (plex == null || episodePlayback?.serverId != plex.serverId || episodePlayback?.currentKey != plex.ratingKey) {
            episodeRequestToken++
            episodePlayback = null
            episodeLoadFailure = null
            episodeSwitchBusy = false
        }
        playingTitle = record.title
        subtitleUri = record.subtitleUri
        subtitleName = record.subtitleName
        initialPosition = videoStartPosition(record, exactPosition)
        sourceDuration = record.durationMs.coerceAtLeast(0)
        exactPlaybackPosition = exactPosition
        preferRemotePosition = remotePosition || exactPosition
        playingUri = record.uri
    }

    fun openPlexEpisode(config: PlexServerConfig, movie: PlexMovie, part: PlexPart, remotePosition: Boolean = true) {
        val uri = PlexUris.build(config.id, movie.ratingKey, part.key)
        open(RecentVideo(uri.toString(), plexPlaybackTitle(movie), if (movie.watched) 0L else movie.viewOffsetMs, movie.durationMs), remotePosition)
        loadEpisodeNeighbors(config, movie, uri.toString())
    }

    fun startPlex(config: PlexServerConfig, movie: PlexMovie, part: PlexPart, castEpisode: Boolean = false) {
        versionPreferences.save(config, movie, part)
        val localPosition = if (movie.playFromStart || movie.watched) null else plexStore.preferredProgress(movie)
        val selectedMovie = if (movie.playFromStart) movie.copy(viewOffsetMs = 0, watched = false) else if (localPosition != null) movie.copy(viewOffsetMs = localPosition, watched = false) else movie
        if (castEpisode) {
            val nextUri = PlexUris.build(config.id, selectedMovie.ratingKey, part.key)
            castController.configureEpisode(nextUri.toString(), selectedMovie, episodePlayback?.episodes.orEmpty(), exactStart = false)
            check(castController.replaceCurrent(nextUri, plexPlaybackTitle(selectedMovie),
                resumePositionMs(if (selectedMovie.watched) 0L else selectedMovie.viewOffsetMs, selectedMovie.durationMs), selectedMovie.durationMs,
                com.megatron.player.cast.castMimeType(nextUri, part.filePath))) { "当前电视暂时无法切集，请等待连接或操作完成后重试" }
        }
        if (movie.type == "episode") openPlexEpisode(config, selectedMovie, part)
        else open(RecentVideo(PlexUris.build(config.id, movie.ratingKey, part.key).toString(), movie.title,
            if (selectedMovie.watched) 0L else selectedMovie.viewOffsetMs, selectedMovie.durationMs), remotePosition = true)
    }

    fun requestPlex(config: PlexServerConfig, movie: PlexMovie, part: PlexPart) {
        if (movie.parts.size > 1) versionRequest = PlexVersionRequest(config, movie)
        else startPlex(config, movie, part)
    }

    fun switchToEpisode(ratingKey: String) {
        val context = episodePlayback ?: return
        if (episodeSwitchBusy) return
        val expected = context.currentKey
        val target = context.episodes.firstOrNull { it.ratingKey == ratingKey } ?: return
        if (target.ratingKey == expected) return
        val timerGeneration = sleepTimer.pauseGeneration
        val expectedUri = playingUri
        val wasCasting = castController.isCasting(expectedUri)
        episodeSwitchBusy = true
        scope.launch {
            try {
                val credentials = plexStore.credentials(context.serverId) ?: error("Plex 连接已移除")
                val detail = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).detail(credentials, target.ratingKey) }
                if (detail.ratingKey != target.ratingKey || !detail.type.equals("episode", ignoreCase = true) || (detail.grandparentRatingKey.isNotBlank() && detail.grandparentRatingKey != context.showKey)) {
                    error("Plex 返回的剧集不属于当前剧集")
                }
                val part = versionPreferences.selected(credentials.config, detail) ?: error("所选剧集没有可直接播放的文件")
                val currentRef = expectedUri?.let { PlexUris.parse(Uri.parse(it)) }
                if (timerGeneration != sleepTimer.pauseGeneration) return@launch
                if (playingUri != expectedUri || episodePlayback?.currentKey != expected || currentRef?.serverId != context.serverId || currentRef?.ratingKey != expected) return@launch
                subtitleUri = null
                subtitleName = null
                subtitleWasPlaying = false
                if (wasCasting && !castController.isCasting(expectedUri)) error("投屏已结束，请重新连接电视后切集")
                startPlex(credentials.config, detail.copy(grandparentRatingKey = context.showKey), part, castEpisode = wasCasting)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (episodePlayback?.currentKey == expected && playingUri == expectedUri) message = "切换剧集失败：${plexErrorMessage(error)}"
            } finally {
                if (episodePlayback?.currentKey == expected && playingUri == expectedUri) episodeSwitchBusy = false
            }
        }
    }

    fun switchEpisode(direction: Int) {
        val context = episodePlayback ?: return
        val target = if (direction < 0) context.previous else context.next
        target?.let { switchToEpisode(it.ratingKey) }
    }

    fun retryEpisodes() {
        val context = episodePlayback ?: return
        if (context.loading) return
        episodePlayback = context.copy(loading = true)
        episodeLoadFailure = null
        val expectedUri = playingUri
        scope.launch {
            try {
                val credentials = plexStore.credentials(context.serverId) ?: error("Plex 连接已移除")
                val detail = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).detail(credentials, context.currentKey) }
                if (episodePlayback?.currentKey != context.currentKey || playingUri != expectedUri) return@launch
                loadEpisodeNeighbors(credentials.config, detail, expectedUri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (episodePlayback?.currentKey == context.currentKey && playingUri == expectedUri) {
                    episodePlayback = episodePlayback?.copy(loading = false)
                    episodeLoadFailure = plexErrorMessage(error)
                    message = "剧集列表加载失败：${plexErrorMessage(error)}"
                }
            }
        }
    }


    LaunchedEffect(castState.uri, castState.priorEpisodeUri) {
        if (castState.priorEpisodeUri != null && playingUri == castState.priorEpisodeUri && (castState.busy || castState.active)) {
            val movie = castController.currentEpisode ?: return@LaunchedEffect
            val ref = PlexUris.parse(Uri.parse(castState.uri)) ?: return@LaunchedEffect
            val config = plexStore.config()?.takeIf { it.id == ref.serverId } ?: return@LaunchedEffect
            open(RecentVideo(castState.uri, castState.title, castState.positionMs, castState.durationMs), remotePosition = true)
            loadEpisodeNeighbors(config, movie, castState.uri)
        }
    }

    LaunchedEffect(playingUri) {
        if (playingUri != null) MusicPlaybackGate.pauseForVideo()
        val uriText = playingUri ?: return@LaunchedEffect
        val ref = PlexUris.parse(Uri.parse(uriText)) ?: return@LaunchedEffect
        if (episodePlayback?.serverId == ref.serverId && episodePlayback?.currentKey == ref.ratingKey) return@LaunchedEffect
        val token = episodeRequestToken + 1L
        episodeRequestToken = token
        episodePlayback = null
        episodeLoadFailure = null
        try {
            val credentials = plexStore.credentials(ref.serverId) ?: return@LaunchedEffect
            val movie = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).detail(credentials, ref.ratingKey) }
            if (playingUri != uriText || token != episodeRequestToken) return@LaunchedEffect
            if (movie.type.equals("episode", ignoreCase = true)) loadEpisodeNeighbors(credentials.config, movie, uriText)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (playingUri == uriText && token == episodeRequestToken) message = "剧集信息恢复失败：${plexErrorMessage(error)}"
        }
    }

    fun openUri(uri: Uri, title: String = displayName(context, uri)) {
        val text = uri.toString()
        val old = historyStore.find(text)
        open(old?.copy(title = title) ?: RecentVideo(text, title))
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadAccess(context, uri)
            openUri(uri)
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                val config = MediaSourceConfig(UUID.randomUUID().toString(), displayName(context, document), SourceKind.LOCAL, uri.toString())
                sourceStore.save(config, null)
                sourceRevision++
            } catch (_: Exception) { message = "目录授权失败，请重新选择可访问的目录。" }
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri)
            if (subtitleMimeType(name) == null) message = "请选择 SRT、VTT、ASS 或 SSA 字幕文件"
            else {
                persistReadAccess(context, uri)
                subtitleName = name
                subtitleUri = uri.toString()
            }
        }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); message = null }
    }
    LaunchedEffect(request) {
        val uri = request.uri ?: return@LaunchedEffect
        when {
            uri.scheme == "content" -> openUri(uri)
            validatedHttpUrl(uri.toString()) != null -> openUri(Uri.parse(validatedHttpUrl(uri.toString())))
            else -> message = "请选择本地视频，或使用 HTTP(S) 视频直链"
        }
    }

    MaterialTheme(colorScheme = PlayerTheme.colors) {
        Surface(Modifier.fillMaxSize(), color = PlayerTheme.background) {
            Box(Modifier.fillMaxSize()) {
                val currentUri = playingUri
                if (currentUri == null) {
                    browserStateHolder.SaveableStateProvider("file-browser") {
                        FileBrowserScreen(
                            history = history,
                            plexPageMemory = plexPageMemory,
                            plexSession = plexBrowseSession,
                            onPlay = { uri, name -> openUri(uri, name) },
                            onPlayPlex = ::requestPlex,
                            onOpenLocal = {
                                try { videoPicker.launch(arrayOf("video/*", "application/octet-stream")) }
                                catch (_: android.content.ActivityNotFoundException) { message = "当前系统没有可用的文件选择器。" }
                            },
                            onChooseDirectory = {
                                try { directoryPicker.launch(null) }
                                catch (_: android.content.ActivityNotFoundException) { message = "当前系统没有可用的目录选择器。" }
                            },
                            onOpenUrl = { raw ->
                                val valid = validatedHttpUrl(raw)
                                if (valid == null) message = "请输入有效的 HTTP(S) 视频直链；地址中不要包含用户名密码"
                                else openUri(Uri.parse(valid))
                            },
                            onOpenCast = { current -> open(current, exactPosition = true) },
                            onPlayRecent = { recent ->
                                val ref = PlexUris.parse(Uri.parse(recent.uri))
                                if (ref == null) open(recent)
                                else scope.launch {
                                    try {
                                        val supplied = plexStore.credentials(ref.serverId) ?: error("连接已移除")
                                        val updated = withContext(Dispatchers.IO) {
                                            PlexClient(plexStore.clientIdentifier).detail(supplied, ref.ratingKey)
                                        }
                                        val part = versionPreferences.selected(supplied.config, updated) ?: error("影片没有可直接播放的文件")
                                        requestPlex(supplied.config, if (historyCompleted(recent)) updated.copy(playFromStart = true) else updated, part)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { message = plexErrorMessage(error) }
                                }
                            },
                            onPlayTimePoint = { point ->
                                val ref = PlexUris.parse(Uri.parse(point.uri))
                                if (ref == null) open(point, exactPosition = true)
                                else scope.launch {
                                    try {
                                        val supplied = plexStore.credentials(ref.serverId) ?: error("Plex 连接已移除")
                                        val updated = withContext(Dispatchers.IO) { PlexClient(plexStore.clientIdentifier).detail(supplied, ref.ratingKey) }
                                        check(updated.parts.any { it.key == ref.partKey }) { "收藏对应的视频文件已变更，请重新选择文件；可删除原时间点。" }
                                        check(plexStore.config()?.let { it.id == supplied.config.id && it.machineIdentifier == supplied.config.machineIdentifier } == true) { "Plex 连接已变更，请重新打开收藏。" }
                                        open(point, exactPosition = true)
                                        loadEpisodeNeighbors(supplied.config, updated, point.uri)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { message = plexErrorMessage(error) }
                                }
                            },
                            onDeleteRecent = { historyStore.remove(it.uri); history = historyStore.list() },
                            onClearHistory = { historyStore.clear(); history = historyStore.list() },
                            sourceRevision = sourceRevision,
                            musicRepository = musicRepository, musicPlayback = musicPlayback, musicUi = musicUi,
                        )
                    }
                } else {
                    PlayerScreen(
                        uri = Uri.parse(currentUri), title = playingTitle,
                        sourceDurationMs = sourceDuration, exactStart = exactPlaybackPosition,
                        subtitleUri = subtitleUri?.let(Uri::parse), subtitleName = subtitleName,
                        subtitlePlayWhenReady = subtitleWasPlaying,
                        resumeMs = if (preferRemotePosition) initialPosition else historyStore.find(currentUri)?.let { resumePositionMs(it.positionMs, it.durationMs) } ?: initialPosition,
                        previousEpisodeTitle = episodePlayback?.previous?.let(::plexPlaybackTitle),
                        nextEpisodeTitle = episodePlayback?.next?.let(::plexPlaybackTitle),
                        episodes = episodePlayback?.episodes ?: emptyList(),
                        currentEpisodeKey = episodePlayback?.currentKey,
                        seriesSkipKey = episodePlayback?.let { episode -> plexStore.config()?.takeIf {
                            it.id == episode.serverId && it.machineIdentifier.isNotBlank()
                        }?.let { SeriesSkipKey(it.machineIdentifier, episode.showKey) } },
                        episodesLoading = episodePlayback?.loading == true,
                        episodeSwitchBusy = episodeSwitchBusy,
                        onSelectEpisode = { switchToEpisode(it) },
                        onRetryEpisodes = { retryEpisodes() },
                        autoPlayNext = autoPlayNext,
                        onAutoPlayNextChange = { autoPlayNext = it; playbackPreferences.edit().putBoolean("auto_play_next", it).apply() },
                        onPreviousEpisode = { switchEpisode(-1) },
                        onNextEpisode = { switchEpisode(1) },
                        onBack = {
                            PlexUris.parse(Uri.parse(currentUri))?.let { plexBrowseSession.refreshAfterPlayback(it.ratingKey, episodePlayback?.showKey) }
                            episodeRequestToken++; episodePlayback = null; playingUri = null; history = historyStore.list()
                        },
                        onChooseSubtitle = { _, play ->
                            subtitleWasPlaying = play
                            subtitlePicker.launch(arrayOf("*/*"))
                        },
                        onRemoveSubtitle = { subtitleUri = null; subtitleName = null; subtitleWasPlaying = false },
                    )
                }
                versionRequest?.let { pending ->
                    PlexVersionPicker(pending.movie, versionPreferences.selected(pending.config, pending.movie),
                        onDismiss = { versionRequest = null }, onPlay = { chosen ->
                            versionRequest = null
                            if (plexStore.config()?.id == pending.config.id) startPlex(pending.config, pending.movie, chosen)
                            else message = "Plex 连接已变更，请重新打开影片。"
                        })
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding())
            }
        }
    }
}

private fun persistReadAccess(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Some providers only grant access for this session; Media3 can still open that URI.
    }
}

private fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "http" || uri.scheme == "https") return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "网络视频"
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getString(index)
        }
    } catch (_: Exception) {
        // A revoked or unavailable document is surfaced by the player's error UI.
    }
    return "本地视频"
}
