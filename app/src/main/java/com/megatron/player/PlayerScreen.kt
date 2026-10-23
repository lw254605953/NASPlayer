package com.megatron.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.megatron.player.cast.*
import com.megatron.player.plex.PlexMovie
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun PlayerScreen(
    uri: android.net.Uri,
    title: String,
    subtitleUri: android.net.Uri?,
    subtitleName: String?,
    subtitlePlayWhenReady: Boolean,
    resumeMs: Long,
    onBack: () -> Unit,
    onChooseSubtitle: (positionMs: Long, playWhenReady: Boolean) -> Unit,
    onRemoveSubtitle: () -> Unit,
    previousEpisodeTitle: String? = null,
    nextEpisodeTitle: String? = null,
    autoPlayNext: Boolean = true,
    onAutoPlayNextChange: (Boolean) -> Unit = {},
    onPreviousEpisode: () -> Unit = {},
    onNextEpisode: () -> Unit = {},
    episodes: List<PlexMovie> = emptyList(),
    currentEpisodeKey: String? = null,
    episodesLoading: Boolean = false,
    episodeSwitchBusy: Boolean = false,
    onSelectEpisode: (String) -> Unit = {},
    onRetryEpisodes: () -> Unit = {},
    sourceDurationMs: Long = 0,
    exactStart: Boolean = false,
    seriesSkipKey: SeriesSkipKey? = null,
) {
    val context = LocalContext.current
    val caster = remember { CastController.shared(context) }
    val cast by caster.state.collectAsState()
    val remoteForVideo = cast.confirmed && cast.uri == uri.toString()
    var castMode by rememberSaveable(uri.toString()) { mutableStateOf(cast.uri == uri.toString() && (cast.active || cast.busy)) }
    var autoCastConnect by remember(uri) { mutableStateOf(true) }
    val castingUi = castMode || cast.uri == uri.toString() && (cast.active || cast.busy)
    val latestRemote by rememberUpdatedState(remoteForVideo)
    val latestCast by rememberUpdatedState(cast)
    val collections = remember { VideoCollections(context) }
    val collectionScope = rememberCoroutineScope()
    var collectionRevision by remember(uri) { mutableIntStateOf(0) }
    var favoriteBusy by remember(uri) { mutableStateOf(false) }
    var pointDraft by remember(uri) { mutableStateOf<RecentVideo?>(null) }
    var renamePoint by remember(uri) { mutableStateOf<VideoTimePoint?>(null) }
    val videoFavorite = remember(uri, collectionRevision) { runCatching { collections.isFavorite(uri.toString()) }.getOrDefault(false) }
    val timePoints = remember(uri, collectionRevision) { runCatching { collections.pointsFor(uri.toString()) }.getOrDefault(emptyList()) }
    val activity = context as? Activity
    val lifecycleOwner = context as? LifecycleOwner
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val previousOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var playbackState by remember(uri) { mutableIntStateOf(Player.STATE_IDLE) }
    var errorCode by remember(uri) { mutableStateOf<String?>(null) }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var positionMs by remember(uri) { mutableLongStateOf(resumeMs.coerceAtLeast(0L)) }
    var durationMs by remember(uri) { mutableLongStateOf(sourceDurationMs.coerceAtLeast(0)) }
    var subtitleSizeFraction by remember(uri) { mutableFloatStateOf(PlaybackPreferencesStore(context).read().subtitleSize.fraction) }
    var sessionRevision by remember(uri) { mutableIntStateOf(0) }
    var seriesSettings by remember(uri) { mutableStateOf(false) }
    if (seriesSettings && seriesSkipKey != null) SeriesSkipDialog(seriesSkipKey) { seriesSettings = false }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var panel by remember(uri) { mutableStateOf<PlayerPanel?>(null) }
    var interactionTick by remember(uri) { mutableIntStateOf(0) }
    var touchLocked by remember { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playerView by remember(uri) { mutableStateOf<GesturePlayerView?>(null) }
    var seekPreview by remember(uri) { mutableStateOf<Long?>(null) }
    var seekStart by remember(uri) { mutableLongStateOf(0L) }
    var canSeek by remember(uri) { mutableStateOf(false) }
    var autoAdvanceTriggered by remember(uri) { mutableStateOf(false) }
    val session = remember(uri) {
        PlayerSession(context, uri, title, subtitleUri, subtitleName) { changed ->
            playbackState = changed.player.playbackState
            isPlaying = changed.player.isPlaying
            positionMs = changed.player.currentPosition.coerceAtLeast(0L)
            durationMs = castDurationMs(changed.player.duration, maxOf(durationMs, sourceDurationMs))
            canSeek = changed.canSeek()
            errorCode = changed.lastError?.errorCodeName
            sessionRevision++
        }
    }
    val latestOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val latestOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val latestOnSelectEpisode by rememberUpdatedState(onSelectEpisode)
    val latestOnAutoPlayNextChange by rememberUpdatedState(onAutoPlayNextChange)
    val latestBusy by rememberUpdatedState(episodeSwitchBusy)
    val latestPanel by rememberUpdatedState(panel)
    val latestLocked by rememberUpdatedState(touchLocked)

    fun interact() { interactionTick++ }
    fun showControls() { controlsVisible = true; interact() }
    fun openPanel(value: PlayerPanel) {
        if (value == PlayerPanel.INFO) playerView?.let(session::updateDisplayInfo)
        if (value == PlayerPanel.CAST) {
            autoCastConnect = !castingUi
            session.player.pause()
            positionMs = (if (remoteForVideo) cast.positionMs else session.player.currentPosition).coerceAtLeast(0)
            durationMs = castDurationMs(if (remoteForVideo) cast.durationMs else session.player.duration, maxOf(durationMs, sourceDurationMs))
            castMode = true; touchLocked = false; seekPreview = null
        }
        panel = value; showControls()
    }
    fun closePanel() { panel = null; showControls() }
    fun leavePlayer() { session.saveIfNeeded(force = true); onBack() }
    fun updateFromSession() {
        positionMs = session.player.currentPosition.coerceAtLeast(0L)
        durationMs = castDurationMs(session.player.duration, maxOf(durationMs, sourceDurationMs))
        canSeek = session.canSeek()
        subtitleSizeFraction = session.subtitleTextSizeFraction()
        sessionRevision++
        interact()
    }
    fun saveThen(action: () -> Unit) {
        if (latestBusy) return
        session.saveIfNeeded(force = true)
        action()
    }
    fun returnToPhone() {
        val current = caster.state.value
        val target = if (current.uri == uri.toString() && (current.active || current.busy || session.progressDelegatedToCast)) current.positionMs
            else session.player.currentPosition.coerceAtLeast(0)
        if (current.uri == uri.toString() && (current.active || current.busy)) caster.stop()
        // Reclaim synchronously so the remote-state effect cannot pause an explicit local resume.
        if (session.progressDelegatedToCast) session.reclaimProgressFromCast(target)
        else session.seekToPosition(target)
        castMode = false; panel = null
        if (session.lastError != null) session.retry() else session.player.play()
        showControls()
    }

    fun currentVideo() = RecentVideo(uri.toString(), title, (if (remoteForVideo) cast.positionMs else session.player.currentPosition).coerceAtLeast(0L),
        (if (remoteForVideo) cast.durationMs else session.player.duration).coerceAtLeast(0L), session.subtitleUri?.toString(), session.externalSubtitleName)
    fun captureTimePoint() {
        pointDraft = currentVideo() // Freeze the click-time position even while naming with playback continuing.
        openPanel(PlayerPanel.BOOKMARKS)
    }
    fun collectionAction(action: () -> Unit) {
        try { action(); collectionRevision++ }
        catch (_: Exception) { Toast.makeText(context, "收藏保存失败，请重试", Toast.LENGTH_SHORT).show() }
    }

    // Hide only on a real playing transition. Normal recomposition must not hide user-revealed controls.
    LaunchedEffect(isPlaying, uri) {
        if (isPlaying && panel == null) controlsVisible = false
        else if (!isPlaying && !touchLocked) controlsVisible = true
    }
    LaunchedEffect(controlsVisible, isPlaying, panel, touchLocked, seekPreview, interactionTick) {
        if (controlsVisible && isPlaying && panel == null && !touchLocked && seekPreview == null) {
            delay(5_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(activity, fullscreen, rotationLocked) {
        activity?.requestedOrientation = when {
            rotationLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> previousOrientation
        }
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                if (fullscreen) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                } else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = previousOrientation
            activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    LaunchedEffect(remoteForVideo) {
        if (remoteForVideo) {
            session.delegateProgressToCast(); castMode = true
            if (panel == PlayerPanel.CAST) panel = null
            showControls()
        }
        else if (session.progressDelegatedToCast && cast.uri == uri.toString()) { session.reclaimProgressFromCast(cast.positionMs); showControls() }
    }
    LaunchedEffect(uri, sourceDurationMs, durationMs) { caster.updateDuration(uri, castDurationMs(durationMs, sourceDurationMs)) }
    LaunchedEffect(session, currentEpisodeKey, exactStart, seriesSkipKey) {
        session.configureEpisodeSkipping(currentEpisodeKey != null, exactStart, seriesSkipKey)
    }
    LaunchedEffect(session) {
        session.configureEpisodeSkipping(currentEpisodeKey != null, exactStart, seriesSkipKey)
        val currentReceiver = caster.state.value
        if ((currentReceiver.active || currentReceiver.busy) && currentReceiver.uri != uri.toString()) caster.stop()
        val remote = caster.state.value.takeIf { (it.confirmed || it.busy || it.active) && it.uri == uri.toString() }
        if (remote?.confirmed == true) session.delegateProgressToCast()
        session.prepare(remote?.positionMs ?: resumeMs, playWhenReady = remote == null && !castingUi)
        while (true) {
            session.applyEpisodeSkips()
            session.sampleVideoQuality()
            if (panel == PlayerPanel.INFO) sessionRevision++
            playbackState = session.player.playbackState
            isPlaying = session.player.isPlaying
            positionMs = if (latestRemote) latestCast.positionMs else session.player.currentPosition.coerceAtLeast(0L)
            durationMs = castDurationMs(if (latestRemote) latestCast.durationMs else session.player.duration, maxOf(durationMs, sourceDurationMs))
            errorCode = session.lastError?.errorCodeName
            canSeek = if (latestRemote) latestCast.canSeek else session.canSeek()
            session.saveIfNeeded()
            delay(500L)
        }
    }
    LaunchedEffect(session, subtitleUri, subtitleName) {
        if (subtitleUri != session.subtitleUri) {
            session.replaceSubtitle(subtitleUri, subtitleName)
            if (subtitleUri != null) session.player.playWhenReady = subtitlePlayWhenReady
        }
    }
    LaunchedEffect(playbackState, uri, autoPlayNext, nextEpisodeTitle, episodeSwitchBusy) {
        if (!castingUi && playbackState == Player.STATE_ENDED && autoPlayNext && nextEpisodeTitle != null && !autoAdvanceTriggered && !episodeSwitchBusy &&
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) {
            autoAdvanceTriggered = true
            val timerGeneration = SleepTimer.shared(context).pauseGeneration
            saveThen { if (timerGeneration == SleepTimer.shared(context).pauseGeneration) latestOnNextEpisode() }
        }
    }
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { session.player.pause(); session.saveIfNeeded(force = true) }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer); session.release() }
    }
    BackHandler {
        when {
            touchLocked -> Toast.makeText(context, "请先点击锁图标解锁", Toast.LENGTH_SHORT).show()
            panel != null -> if (castingUi) closePanel() else if (panel in listOf(PlayerPanel.INFO, PlayerPanel.PREFERENCES, PlayerPanel.BOOKMARKS, PlayerPanel.CAST, PlayerPanel.SLEEP_TIMER)) openPanel(PlayerPanel.MORE) else closePanel()
            fullscreen -> fullscreen = false
            else -> leavePlayer()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).then(if (fullscreen) Modifier.displayCutoutPadding() else Modifier.safeDrawingPadding())) {
        if (!castingUi) key(uri) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.player_view, null) as GesturePlayerView).apply {
                    playerView = this
                    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> session.updateDisplayInfo(view) }
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    player = session.player
                    onTap = { if (!latestLocked && latestPanel == null) { controlsVisible = !controlsVisible; interact() } }
                    onGestureStarted = { if (latestPanel == null) controlsVisible = false }
                    onSeekPreview = { target, start -> seekPreview = target; seekStart = start; interact() }
                    onSeekCommit = { target -> session.seekToPosition(target); updateFromSession() }
                    onGestureFinished = { showControls() }
                }
            }, update = { view ->
                view.player = session.player
                view.keepScreenOn = isPlaying
                view.touchLocked = touchLocked || panel != null || remoteForVideo
                view.resizeMode = resizeMode
                view.subtitleView?.setFractionalTextSize(subtitleSizeFraction)
                val bottom = if (controlsVisible && panel == null && !touchLocked && view.height > 0)
                    (180 * view.resources.displayMetrics.density / view.height).coerceIn(.08f, .45f) else .08f
                view.subtitleView?.setBottomPaddingFraction(bottom)
            }, onRelease = { view -> view.keepScreenOn = false; view.player = null; if (playerView === view) playerView = null })
        }
        if (castingUi) {
            val currentCast = if (cast.uri == uri.toString()) cast else CastState()
            CastRemoteScreen(currentCast, title, positionMs, durationMs, onBack = ::leavePlayer,
                onChooseDevice = { openPanel(PlayerPanel.CAST) }, onToggle = caster::toggle,
                onSeek = caster::seekTo, onStop = ::returnToPhone, onShowInfo = { openPanel(PlayerPanel.INFO) }, onSleepTimer = { openPanel(PlayerPanel.SLEEP_TIMER) }) {
                    if (currentEpisodeKey != null) CastEpisodeList(episodes, currentEpisodeKey, episodesLoading, episodeSwitchBusy || cast.busy,
                        onSelect = { key -> saveThen { latestOnSelectEpisode(key) } })
                }
        }
        if (!castingUi && controlsVisible && !touchLocked && panel == null) {
            PlayerControls(title = title, isPlaying = if (remoteForVideo) cast.playing else isPlaying, positionMs = seekPreview ?: if (remoteForVideo) cast.positionMs else positionMs, durationMs = if (remoteForVideo) cast.durationMs else durationMs,
                canSeek = canSeek, rotationLocked = rotationLocked, fullscreen = fullscreen,
                hasEpisodes = currentEpisodeKey != null || previousEpisodeTitle != null || nextEpisodeTitle != null,
                playbackSpeed = session.player.playbackParameters.speed, audioOffsetMs = session.audioOffsetMs,
                showCenterPlay = remoteForVideo || errorCode == null && playbackState != Player.STATE_BUFFERING,
                onAction = { action ->
                    interact()
                    if (remoteForVideo && action in setOf(PlayerAction.JUMP, PlayerAction.AUDIO, PlayerAction.SUBTITLES, PlayerAction.SPEED, PlayerAction.SYNC)) {
                        openPanel(PlayerPanel.CAST)
                    } else when (action) {
                        PlayerAction.BACK -> if (fullscreen) fullscreen = false else leavePlayer()
                        PlayerAction.PLAY_PAUSE -> { if (remoteForVideo) caster.toggle() else if (session.player.isPlaying) session.player.pause() else { if (playbackState == Player.STATE_ENDED) session.player.seekTo(0); session.player.play() } }
                        PlayerAction.ROTATION_LOCK -> rotationLocked = !rotationLocked
                        PlayerAction.TOUCH_LOCK -> { touchLocked = true; controlsVisible = false }
                        PlayerAction.FULLSCREEN -> if (!rotationLocked) fullscreen = !fullscreen
                        PlayerAction.MORE -> openPanel(PlayerPanel.MORE)
                        PlayerAction.JUMP -> openPanel(PlayerPanel.JUMP)
                        PlayerAction.AUDIO -> openPanel(PlayerPanel.AUDIO)
                        PlayerAction.SUBTITLES -> openPanel(PlayerPanel.SUBTITLES)
                        PlayerAction.SPEED -> openPanel(PlayerPanel.SPEED)
                        PlayerAction.EPISODES -> openPanel(PlayerPanel.EPISODES)
                        PlayerAction.SYNC -> openPanel(PlayerPanel.SYNC)
                    }
                }, onScrub = { preview -> if (seekPreview == null) seekStart = positionMs; seekPreview = preview; interact() },
                onSeek = { target -> if (remoteForVideo) caster.seekTo(target) else { session.seekToPosition(target); updateFromSession() } }, onInteraction = ::interact)
        }
        if (touchLocked) {
            IconButton(onClick = { touchLocked = false; showControls() }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                .size(52.dp).background(Color(0xBB222527), CircleShape)) { PlayerGlyph("lock-keyhole-open", "解锁触摸", Modifier.size(24.dp)) }
        }
        seekPreview?.let { target ->
            Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium, color = Color(0xED222527), contentColor = Color.White) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatPlaybackTime(target), style = MaterialTheme.typography.headlineSmall)
                    val delta = target - seekStart
                    Text("${if (delta >= 0) "+" else "−"}${formatPlaybackTime(kotlin.math.abs(delta))} · 松手跳转", color = Color(0xFFE6BF7A), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!castingUi && errorCode != null && !touchLocked && panel == null) {
            val guide = session.failureGuide()
            Surface(Modifier.align(Alignment.Center).padding(24.dp), color = Color(0xED17191B), contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(guide.title, style = MaterialTheme.typography.titleMedium)
                    Text(guide.message, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { session.retry(); showControls() }, modifier = Modifier.fillMaxWidth()) { Text(guide.action) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openPanel(PlayerPanel.INFO) }, modifier = Modifier.weight(1f)) { Text("查看诊断") }
                        TextButton(onClick = ::leavePlayer, modifier = Modifier.weight(1f)) { Text("返回检查片源") }
                    }
                }
            }
        } else if (!castingUi && playbackState == Player.STATE_BUFFERING && !touchLocked && panel == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = Color(0xFFE6BF7A), strokeWidth = 2.dp)
        }
        pointDraft?.let { draft ->
            TimePointNameDialog("${draft.title} · ${formatPlaybackTime(draft.positionMs)}", "收藏时间点 · ${formatPlaybackTime(draft.positionMs)}",
                onDismiss = { pointDraft = null }, onSave = { name -> collectionAction { collections.addPoint(draft, name); pointDraft = null } })
        }
        renamePoint?.let { point -> TimePointNameDialog(point.name, "重命名时间点", onDismiss = { renamePoint = null },
            onSave = { name -> collectionAction { collections.rename(point, name); renamePoint = null } }) }
        panel?.let { currentPanel ->
            PlayerPanelLayout(currentPanel.title, onDismiss = ::closePanel,
                onBack = if (!castingUi && currentPanel in listOf(PlayerPanel.INFO, PlayerPanel.PREFERENCES, PlayerPanel.BOOKMARKS, PlayerPanel.CAST, PlayerPanel.SLEEP_TIMER)) ({ openPanel(PlayerPanel.MORE) }) else null) {
                if (currentPanel == PlayerPanel.CAST) CastPanel(caster, uri, title, positionMs, durationMs,
                    castMimeType(uri, title, session.player.videoFormat?.containerMimeType), autoConnect = autoCastConnect, onSelected = ::closePanel)
                else if (currentPanel == PlayerPanel.BOOKMARKS) VideoTimePointsPanel(timePoints,
                    if (castingUi) remoteForVideo && cast.canSeek && !cast.busy else canSeek, ::captureTimePoint,
                    onPlay = {
                        if (remoteForVideo) { caster.seekTo(it.video.positionMs); closePanel() }
                        else if (session.seekToPosition(it.video.positionMs)) { session.player.play(); updateFromSession(); closePanel() }
                    },
                    onRename = { renamePoint = it }, onDelete = { collectionAction { collections.remove(it) } })
                else PlayerPanelContent(currentPanel, session, sessionRevision, positionMs, durationMs, session.externalSubtitleName, session.subtitleUri != null,
                    episodes, currentEpisodeKey, episodesLoading, episodeSwitchBusy, previousEpisodeTitle, nextEpisodeTitle,
                    autoPlayNext, resizeMode, onChanged = ::updateFromSession, onPanelChange = ::openPanel, onDismiss = ::closePanel,
                    onChooseSubtitle = { closePanel(); onChooseSubtitle(session.player.currentPosition, session.player.playWhenReady) },
                    onRemoveSubtitle = { session.replaceSubtitle(null, null); onRemoveSubtitle(); updateFromSession() },
                    onCopy = { clipboard.setPrimaryClip(ClipData.newPlainText("播放信息", session.diagnostics())); Toast.makeText(context, "已复制播放信息", Toast.LENGTH_SHORT).show() },
                    onSelectEpisode = { key -> saveThen { latestOnSelectEpisode(key) } }, onRetryEpisodes = onRetryEpisodes,
                    onPreviousEpisode = { saveThen { latestOnPreviousEpisode() } }, onNextEpisode = { saveThen { latestOnNextEpisode() } },
                    onAutoPlayNextChange = { latestOnAutoPlayNextChange(it) }, onResizeModeChange = { resizeMode = it },
                    videoFavorite = videoFavorite, favoriteBusy = favoriteBusy, onCaptureTimePoint = ::captureTimePoint,
                    onSeriesSkipSettings = if (seriesSkipKey != null) ({ closePanel(); seriesSettings = true }) else null,
                    onToggleVideoFavorite = {
                        if (!favoriteBusy) collectionScope.launch {
                            favoriteBusy = true
                            try {
                                val saved = collections.toggleFavorite(currentVideo()); collectionRevision++
                                Toast.makeText(context, if (saved) "已收藏视频" else "已取消收藏", Toast.LENGTH_SHORT).show()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Toast.makeText(context, "视频收藏失败，请检查片源连接后重试", Toast.LENGTH_SHORT).show() }
                            finally { favoriteBusy = false }
                        }
                    })
            }
        }
    }
}

private fun errorHint(code: String): String = when {
    code.contains("DECODING") || code.contains("DECODER") -> "当前设备可能无法解码这条音视频轨道。请尝试其他文件或切换音轨。"
    code.contains("PERMISSION") || code.contains("FILE_NOT_FOUND") -> "文件已移动或读取授权已失效，请重新选择视频或字幕。"
    code.contains("NETWORK") || code.contains("HTTP") -> "请检查网络和视频直链是否可以直接访问。"
    code.contains("PARSING") -> "文件或字幕格式暂不支持，可以移除外挂字幕后再试。"
    else -> "请检查文件、网络和字幕，可打开播放信息反馈。"
}
