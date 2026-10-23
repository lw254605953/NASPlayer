package com.megatron.player.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.datasource.DataSource
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.megatron.player.PlaybackHistory
import com.megatron.player.RecentVideo
import com.megatron.player.EpisodeSkipPolicy
import com.megatron.player.SeriesSkipPreferences
import com.megatron.player.SeriesSkipKey
import com.megatron.player.EpisodeSkipTimes
import com.megatron.player.PlexVersionPreferences
import com.megatron.player.resumePositionMs
import com.megatron.player.plex.*
import com.megatron.player.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// One real receiver session, shared across navigation. A random relay URL never contains NAS secrets.
data class CastState(
    val uri: String = "", val title: String = "", val device: String = "", val protocol: String = "",
    val busy: Boolean = false, val active: Boolean = false, val confirmed: Boolean = false,
    val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0,
    val canPause: Boolean = true, val canSeek: Boolean = true, val error: String? = null,
    val completed: Boolean = false, val priorEpisodeUri: String? = null,
)
private data class CastRequest(val uri: Uri, val title: String, val position: Long, val duration: Long, val mime: String, val priorEpisodeUri: String? = null)
private data class CastEpisode(val uri: String, val movie: PlexMovie, val episodes: List<PlexMovie>, val exactStart: Boolean)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CastController private constructor(context: Context) {
    private val app = context.applicationContext
    private var sleepPauseRequested = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices
    private val _discovery = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovery
    private val _discoveryError = MutableStateFlow<String?>(null)
    val discoveryError: StateFlow<String?> = _discoveryError
    private val _google = MutableStateFlow<String?>("未启用")
    val googleMessage: StateFlow<String?> = _google
    private val dlna = DlnaClient(app)
    private val deviceMemory = app.getSharedPreferences("cast_device_v1", Context.MODE_PRIVATE)
    private var discoveryJob: Deferred<List<DlnaDevice>>? = null
    private var google: CastContext? = null
    private var request: CastRequest? = null
    private val skipPreferences = SeriesSkipPreferences(app)
    private var effectiveEpisodeSkip = EpisodeSkipTimes(0, 0)
    private var episode: CastEpisode? = null
    private var skipPolicy = EpisodeSkipPolicy()
    private var skipUri: String? = null
    val currentEpisode: PlexMovie? get() = episode?.takeIf { it.uri == _state.value.uri }?.movie

    fun configureEpisode(uri: String, movie: PlexMovie, episodes: List<PlexMovie>, exactStart: Boolean) {
        if (!movie.type.equals("episode", ignoreCase = true)) return
        val old = episode?.takeIf { it.uri == uri }
        if (skipUri == uri && !_state.value.active && !_state.value.busy) skipUri = null
        episode = CastEpisode(uri, movie,
            episodes.ifEmpty { old?.episodes.orEmpty() },
            if (isCasting(uri) && old != null) old.exactStart else exactStart)
        if (skipUri == uri) skipPolicy.configure(true, episode!!.exactStart)
    }

    private fun episodeRequest(req: CastRequest, seekable: Boolean): CastRequest {
        val info = episode?.takeIf { it.uri == req.uri.toString() }
        if (skipUri != req.uri.toString()) {
            skipPolicy = EpisodeSkipPolicy(); skipUri = req.uri.toString()
            val ref = PlexUris.parse(req.uri)
            val config = PlexStore(app).config()?.takeIf { it.id == ref?.serverId && it.machineIdentifier.isNotBlank() }
            val showKey = info?.movie?.grandparentRatingKey?.takeIf(String::isNotBlank)
            val key = if (config != null && showKey != null) SeriesSkipKey(config.machineIdentifier, showKey) else null
            effectiveEpisodeSkip = skipPreferences.resolve(key)
        }
        skipPolicy.configure(info != null, info?.exactStart == true)
        val preferences = effectiveEpisodeSkip
        val action = skipPolicy.next(req.position, req.duration, seekable, false,
            preferences.introSeconds, preferences.outroSeconds)
        return if (action != null) req.copy(position = action.positionMs) else req
    }
    private var relay: CastRelay? = null
    private var relayMedia: RelayMedia? = null
    private var receiver: DlnaDevice? = null
    private var transport: DlnaClient? = null
    private var playbackSession: PlexPlaybackSession? = null
    private var reporter: PlexProgressReporter? = null
    private var monitor: Job? = null
    private var operation: Job? = null
    private var generation = 0L
    private var lastSaved = 0L
    private var pendingGoogleLoad = false
    private var failures = 0
    private var controlBusy = false
    private var cleanup: Job? = null
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, id: String) { if (request != null) selectGoogle(session) }
        override fun onSessionStartFailed(session: CastSession, error: Int) { fail("Google Cast 连接失败（$error）") }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) {
            if (_state.value.protocol == "Google Cast") stop(sendToReceiver = false)
        }
        override fun onSessionResuming(session: CastSession, id: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) { if (_state.value.protocol == "Google Cast") fail("Google Cast 恢复失败（$error）") }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (_state.value.protocol == "Google Cast") _state.value = _state.value.copy(playing = false, error = "电视连接已暂时中断（$reason）")
        }
    }
    init {
        com.megatron.player.SleepTimer.shared(app).register(this) {
            sleepPauseRequested = true
            applySleepPause()
        }
    }
    private fun applySleepPause() {
        val s = _state.value
        if (sleepPauseRequested && s.confirmed && s.playing && s.canPause && !s.busy) toggle()
    }
    fun stage(uri: Uri, title: String, position: Long, duration: Long, mime: String) {
        request = CastRequest(uri, title, position.coerceAtLeast(0), castDurationMs(duration, request?.takeIf { it.uri == uri }?.duration ?: 0), mime)
        updateDuration(uri, duration)
    }
    fun updateDuration(uri: Uri, duration: Long) {
        if (duration <= 0) return
        request?.takeIf { it.uri == uri }?.let { if (it.duration <= 0) request = it.copy(duration = duration) }
        if (_state.value.uri == uri.toString() && _state.value.durationMs <= 0) _state.value = _state.value.copy(durationMs = duration)
    }
    fun isCasting(uri: String?) = uri != null && _state.value.uri == uri && (_state.value.active || _state.value.busy)
    /** Reuse the selected receiver, but finish the old relay/Stop before issuing the next Play. */
    fun replaceCurrent(uri: Uri, title: String, position: Long, duration: Long, mime: String): Boolean {
        if (!_state.value.confirmed || controlBusy) return false
        val device = receiver
        val session = google?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
        if (device == null && session == null) return false
        stop(sendToReceiver = device != null)
        stage(uri, title, position, duration, mime)
        if (device != null) loadDlna(device) else loadGoogle(session!!)
        return true
    }
    fun prepareGoogle(): Boolean {
        if (google != null) { _google.value = null; return true }
        return runCatching {
            val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
            if (available != ConnectionResult.SUCCESS) {
                _google.value = "Google Play 服务不可用"
                return@runCatching false
            }
            google = CastContext.getSharedInstance(app).also { it.sessionManager.addSessionManagerListener(listener, CastSession::class.java) }
            _google.value = null; true
        }.getOrElse { googleUnavailable(it); false }
    }
    fun googleUnavailable(error: Throwable) {
        android.util.Log.e("PlayerCast", "Google Cast initialization failed; DLNA remains available", error)
        _google.value = "Google Cast 初始化失败（${error.javaClass.simpleName}）"
    }
    fun hasGoogleConnection() = google?.sessionManager?.currentCastSession?.isConnected == true
    fun loadConnectedGoogle() { google?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let(::selectGoogle) }
    /** The saved identity is matched only against fresh discovery, never a saved IP or name. */
    fun rememberedDevice(found: List<DlnaDevice>): DlnaDevice? {
        val id = deviceMemory.getString("dlna_id", null)?.takeIf { it.isNotBlank() } ?: return null
        return found.firstOrNull { it.id == id }
    }
    fun selectDlna(device: DlnaDevice): Boolean {
        if (_state.value.busy) return false
        val req = request ?: return false
        val current = _state.value
        if (current.active && receiver?.id == device.id && current.uri == req.uri.toString()) return true
        if (current.active) {
            val next = if (current.uri == req.uri.toString()) req.copy(position = current.positionMs, duration = castDurationMs(current.durationMs, req.duration)) else req
            stop()
            request = next
        }
        loadDlna(device)
        return true
    }
    private fun selectGoogle(session: CastSession) {
        if (_state.value.busy) return
        val req = request ?: return
        val current = _state.value
        if (current.active) {
            val next = if (current.uri == req.uri.toString()) req.copy(position = current.positionMs, duration = castDurationMs(current.durationMs, req.duration)) else req
            // Keep the newly selected Google session; only the old DLNA receiver needs Stop.
            stop(sendToReceiver = receiver != null)
            request = next
        }
        loadGoogle(session)
    }
    fun discover(): Deferred<List<DlnaDevice>> {
        discoveryJob?.takeIf { !it.isCompleted }?.let { return it }
        _discovery.value = true; _discoveryError.value = null; _devices.value = emptyList()
        return scope.async {
            try {
                withContext(Dispatchers.IO) { dlna.discover() }.also { _devices.value = it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _discoveryError.value = safeError(e); emptyList() }
            finally { _discovery.value = false }
        }.also { discoveryJob = it }
    }
    private fun newFactory(): DataSource.Factory {
        val sources = SourceStore(app); val plex = PlexStore(app); val bindings = PlexWebDavBindingStore(app)
        val session = PlexPlaybackSession(plex.clientIdentifier, learnedPaths = bindings::learned, rememberPath = bindings::remember)
        playbackSession = session
        return PlexDataSourceFactory(AppDataSourceFactory(app, sources::credentials), plex::credentials, plex.clientIdentifier,
            playbackSession = session, webDavBinding = bindings::read, sourceCredentials = sources::credentials)
    }
    private suspend fun begin(req: CastRequest, address: String, name: String, protocol: String): RelayMedia {
        // Called serially on Main; all network/socket work stays on IO.
        val server = CastRelay(newFactory()); relay = server
        _state.value = CastState(req.uri.toString(), req.title, name, protocol, busy = true, positionMs = req.position, durationMs = req.duration, priorEpisodeUri = req.priorEpisodeUri)
        ContextCompat.startForegroundService(app, Intent(app, CastPlaybackService::class.java).putExtra("title", req.title))
        return withContext(Dispatchers.IO) { server.start(req.uri, address, req.mime) }.also { relayMedia = it }
    }
    fun loadDlna(device: DlnaDevice) {
        sleepPauseRequested = false
        if (_state.value.busy || _state.value.active) return
        val req = episodeRequest(request ?: return, device.seekModes.any { it in setOf("REL_TIME", "ABS_TIME") })
        val ticket = ++generation
        _state.value = CastState(req.uri.toString(), req.title, device.name, "DLNA", busy = true, positionMs = req.position, durationMs = req.duration, priorEpisodeUri = req.priorEpisodeUri)
        operation = scope.launch {
            try {
                cleanup?.join()
                receiver = device
                val client = DlnaClient(app).also { transport = it }
                val media = begin(req, device.address, device.name, "DLNA")
                val status = withContext(Dispatchers.IO) { client.load(device, media, req.title, req.position, req.duration) }
                if (ticket != generation) return@launch
                _state.value = _state.value.copy(active = true, canPause = device.canPause, canSeek = device.seekModes.any { it in setOf("REL_TIME", "ABS_TIME") })
                updateDlna(status)
                startMonitor(ticket)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(safeError(e)) }
        }
    }
    private fun loadGoogle(session: CastSession) {
        sleepPauseRequested = false
        if (_state.value.busy || _state.value.active) return
        val req = episodeRequest(request ?: return, true)
        val ticket = ++generation
        _state.value = CastState(req.uri.toString(), req.title, session.castDevice?.friendlyName.orEmpty(), "Google Cast", busy = true, positionMs = req.position, durationMs = req.duration, priorEpisodeUri = req.priorEpisodeUri)
        operation = scope.launch {
            try {
                cleanup?.join()
                val device = session.castDevice ?: error("电视未提供可用地址")
                val address = device.inetAddress?.hostAddress ?: error("电视未提供局域网地址")
                val media = begin(req, address, device.friendlyName, "Google Cast")
                if (ticket != generation) return@launch
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, req.title) }
                val info = MediaInfo.Builder(media.url).setContentType(media.contentType).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metadata).apply { if (req.duration > 0) setStreamDuration(req.duration) }.build()
                val remote = session.remoteMediaClient ?: error("电视媒体控制器不可用")
                pendingGoogleLoad = true
                remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(req.position).setAutoplay(true).build())
                    .setResultCallback { result ->
                        if (ticket == generation) {
                            pendingGoogleLoad = false
                            if (!result.status.isSuccess) fail("电视拒绝播放（Cast ${result.status.statusCode}），请检查电视是否支持此容器与音轨")
                            else { _state.value = _state.value.copy(active = true); startMonitor(ticket) }
                        }
                    }
                // The LOAD callback can be lost after receiver/network failure.
                scope.launch { delay(20_000); if (ticket == generation && pendingGoogleLoad) fail("电视加载超时，请重试或改用 DLNA") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(safeError(e)) }
        }
    }
    private fun startMonitor(ticket: Long) {
        monitor?.cancel(); failures = 0
        monitor = scope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (isActive && ticket == generation && _state.value.active) {
                try {
                    if (receiver != null) updateDlna(withContext(Dispatchers.IO) { (transport ?: error("投屏已结束")).status(receiver!!) }) else updateGoogle()
                    failures = 0
                    applyEpisodeSkips()
                    if (!_state.value.confirmed && android.os.SystemClock.elapsedRealtime() > deadline) {
                        fail("电视未确认开始播放，请检查格式与网络；可重试或回到手机播放"); break
                    }
                    persistProgress()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failures++
                    if (failures >= 3) { stop(sendToReceiver = false); _state.value = _state.value.copy(error = "电视连接中断：${safeError(e)}"); break }
                    _state.value = _state.value.copy(error = "正在重新读取电视状态…")
                }
                delay(1_500)
            }
        }
    }
    private fun updateDlna(status: DlnaStatus) {
        if (status.uri.isNotBlank() && status.uri != relayMedia?.url) {
            stop(sendToReceiver = false)
            _state.value = _state.value.copy(error = "电视已切换到其他视频，本次投屏已断开")
            return
        }
        if (!_state.value.confirmed && status.playing) receiver?.let { device ->
            deviceMemory.edit().putString("dlna_id", device.id).putString("name", device.name).apply()
        }
        val confirmed = _state.value.confirmed || status.playing
        _state.value = _state.value.copy(busy = !confirmed || controlBusy, confirmed = confirmed, playing = status.playing,
            positionMs = status.positionMs.coerceAtLeast(0), durationMs = castDurationMs(status.durationMs, _state.value.durationMs), error = null)
        if (confirmed && status.state in setOf("STOPPED", "NO_MEDIA_PRESENT")) stop(sendToReceiver = false)
    }
    private fun updateGoogle() {
        val remote = google?.sessionManager?.currentCastSession?.remoteMediaClient ?: error("Google Cast 已断开")
        val status = remote.mediaStatus ?: return
        if (status.mediaInfo?.contentId != relayMedia?.url) {
            if (_state.value.confirmed) {
                stop(sendToReceiver = false)
                _state.value = _state.value.copy(error = "电视已切换到其他视频，本次投屏已断开")
            }
            return
        }
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR) error("电视无法解码此视频，请尝试 DLNA 或其他文件版本")
        val playing = status.playerState == MediaStatus.PLAYER_STATE_PLAYING
        // Do not auto-select an older DLNA TV after a successful Google Cast session.
        if (!_state.value.confirmed && playing) deviceMemory.edit().clear().apply()
        val confirmed = _state.value.confirmed || playing
        _state.value = _state.value.copy(busy = !confirmed || controlBusy, confirmed = confirmed, playing = playing,
            positionMs = remote.approximateStreamPosition.coerceAtLeast(0), durationMs = castDurationMs(remote.streamDuration, _state.value.durationMs),
            canSeek = status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK), canPause = status.isMediaCommandSupported(MediaStatus.COMMAND_PAUSE), error = null)
        if (confirmed && status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason in setOf(MediaStatus.IDLE_REASON_FINISHED, MediaStatus.IDLE_REASON_CANCELED)) stop(sendToReceiver = false)
    }
    fun toggle() {
        val s = _state.value
        if (!s.playing) sleepPauseRequested = false
        if (!s.confirmed || s.busy) return
        if (s.playing && !s.canPause) { _state.value = s.copy(error = "此电视未提供暂停功能，可停止投屏"); return }
        command {
            val device = receiver
            if (device != null) withContext(Dispatchers.IO) { if (s.playing) (transport ?: error("投屏已结束")).pause(device) else (transport ?: error("投屏已结束")).play(device) }
            else { val remote = google?.sessionManager?.currentCastSession?.remoteMediaClient ?: error("Google Cast 已断开"); awaitCommand(if (s.playing) remote.pause() else remote.play()) }
        }
    }
    fun seekTo(position: Long) = seekToInternal(position, manual = true)
    private fun seekToInternal(position: Long, manual: Boolean) {
        val s = _state.value
        if (!s.confirmed || !s.canSeek || s.busy) return
        if (manual) {
            val preferences = effectiveEpisodeSkip
            skipPolicy.manualSeek(position, s.durationMs, preferences.introSeconds, preferences.outroSeconds)
        }
        command {
            val target = position.coerceIn(0, s.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            val device = receiver
            if (device != null) withContext(Dispatchers.IO) { (transport ?: error("投屏已结束")).seek(device, target) }
            else { val remote = google?.sessionManager?.currentCastSession?.remoteMediaClient ?: error("Google Cast 已断开"); awaitCommand(remote.seek(MediaSeekOptions.Builder().setPosition(target).build())) }
        }
    }
    private fun applyEpisodeSkips() {
        if (sleepPauseRequested) { applySleepPause(); return }
        val state = _state.value
        if (!state.confirmed || !state.active || state.busy) return
        val info = episode?.takeIf { it.uri == state.uri } ?: return
        skipPolicy.configure(true, info.exactStart)
        val preferences = effectiveEpisodeSkip
        val action = skipPolicy.next(state.positionMs, state.durationMs, state.canSeek, state.playing,
            preferences.introSeconds, preferences.outroSeconds) ?: return
        if (!action.finish) { seekToInternal(action.positionMs, manual = false); return }
        finishEpisode(info)
    }

    /** Finish durably, then reuse the receiver for the next episode even off the remote page. */
    private fun finishEpisode(info: CastEpisode) {
        val prior = _state.value
        val device = receiver
        val googleSession = google?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
        val autoNext = app.getSharedPreferences("player_preferences", Context.MODE_PRIVATE).getBoolean("auto_play_next", true)
        val next = if (autoNext) adjacentPlexEpisode(info.episodes, info.movie.ratingKey, 1) else null
        _state.value = prior.copy(positionMs = prior.durationMs, playing = false, completed = true)
        // Keep the Google session only when we can immediately load the next episode on it.
        stopInternal(sendToReceiver = device != null || next == null,
            keepService = next != null && (device != null || googleSession != null))
        if (next == null || (device == null && googleSession == null)) return
        val ticket = generation
        _state.value = _state.value.copy(busy = true)
        operation = scope.launch {
            try {
                cleanup?.join()
                val ref = PlexUris.parse(Uri.parse(prior.uri)) ?: error("剧集连接已移除")
                val store = PlexStore(app)
                val credentials = store.credentials(ref.serverId) ?: error("Plex 连接已移除")
                val detail = withContext(Dispatchers.IO) { PlexClient(store.clientIdentifier).detail(credentials, next.ratingKey) }
                check(detail.ratingKey == next.ratingKey && detail.type.equals("episode", true) &&
                    (detail.grandparentRatingKey.isBlank() || detail.grandparentRatingKey == info.movie.grandparentRatingKey)) { "下一集资料不匹配" }
                if (ticket != generation) return@launch
                if (sleepPauseRequested) { stopInternal(sendToReceiver = false, keepService = false); return@launch }
                val versions = PlexVersionPreferences(app)
                val part = versions.selected(credentials.config, detail) ?: error("下一集没有可播放文件")
                versions.save(credentials.config, detail, part)
                val uri = PlexUris.build(ref.serverId, detail.ratingKey, part.key)
                configureEpisode(uri.toString(), detail, info.episodes, exactStart = false)
                val position = resumePositionMs(if (detail.watched) 0L else store.preferredProgress(detail) ?: detail.viewOffsetMs, detail.durationMs)
                stage(uri, plexPlaybackTitle(detail), position, detail.durationMs, castMimeType(uri, part.filePath))
                request = request?.copy(priorEpisodeUri = prior.uri)
                _state.value = _state.value.copy(busy = false)
                if (device != null) loadDlna(device) else loadGoogle(googleSession!!)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation) {
                    if (device == null) runCatching { google?.sessionManager?.endCurrentSession(true) }
                    app.stopService(Intent(app, CastPlaybackService::class.java))
                    _state.value = _state.value.copy(busy = false, error = "下一集播放失败：${safeError(e)}")
                }
            }
        }
    }

    private fun command(action: suspend () -> Unit) {
        if (_state.value.busy) return
        controlBusy = true
        _state.value = _state.value.copy(busy = true, error = null)
        val ticket = generation
        operation = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) _state.value = _state.value.copy(error = safeError(e)) }
            finally { if (ticket == generation) { controlBusy = false; _state.value = _state.value.copy(busy = false) } }
        }
    }
    private suspend fun awaitCommand(result: PendingResult<RemoteMediaClient.MediaChannelResult>) = withTimeout(12_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            result.setResultCallback { response ->
                if (continuation.isActive) {
                    if (response.status.isSuccess) continuation.resume(Unit)
                    else continuation.resumeWithException(java.io.IOException("电视控制失败（Cast ${response.status.statusCode}）"))
                }
            }
            continuation.invokeOnCancellation { result.cancel() }
        }
    }
    private fun persistProgress(force: Boolean = false) {
        val s = _state.value
        if (!s.confirmed || s.uri.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastSaved < 5_000) return
        val history = PlaybackHistory(app); val old = history.find(s.uri)
        history.save(RecentVideo(s.uri, s.title, s.positionMs, s.durationMs, old?.subtitleUri, old?.subtitleName, System.currentTimeMillis()))
        if (reporter == null) PlexUris.parse(Uri.parse(s.uri))?.let { reporter = PlexProgressReporter(PlexStore(app), it, title = s.title) }
        reporter?.offer(if (s.completed) "stopped" else if (s.playing) "playing" else "paused", s.positionMs, s.durationMs, s.completed)
        lastSaved = now
    }
    fun stop(sendToReceiver: Boolean = true) = stopInternal(sendToReceiver, keepService = false)
    private fun stopInternal(sendToReceiver: Boolean, keepService: Boolean) {
        val prior = _state.value
        generation++; pendingGoogleLoad = false; controlBusy = false
        operation?.cancel(); operation = null
        monitor?.cancel(); monitor = null
        persistProgress(force = true)
        reporter?.finish(prior.positionMs, prior.durationMs, prior.completed); reporter = null
        val oldReceiver = receiver; receiver = null
        val oldTransport = transport; transport = null
        val oldRelay = relay; relay = null; relayMedia = null
        val oldSession = playbackSession; playbackSession = null
        _state.value = prior.copy(active = false, busy = false, confirmed = false, playing = false)
        if (sendToReceiver && prior.protocol == "Google Cast") runCatching { google?.sessionManager?.endCurrentSession(true) }
        val priorCleanup = cleanup
        cleanup = scope.launch(Dispatchers.IO) {
            priorCleanup?.join()
            oldTransport?.close()
            if (sendToReceiver && oldReceiver != null) runCatching { dlna.stop(oldReceiver) }
            runCatching { oldRelay?.close() }; oldSession?.release()
        }
        if (!keepService) app.stopService(Intent(app, CastPlaybackService::class.java))
    }
    private fun fail(message: String) { stop(); _state.value = _state.value.copy(error = message) }
    private fun safeError(error: Exception) = (error.message ?: "电视连接失败，请检查网络及电视投屏设置")
        .replace(Regex("https?://[^\\s<>]+"), "[设备地址]").take(220)
    companion object {
        @Volatile private var instance: CastController? = null
        fun shared(context: Context): CastController = instance ?: synchronized(this) { instance ?: CastController(context).also { instance = it } }
    }
}
