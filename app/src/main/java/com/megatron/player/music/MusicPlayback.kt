package com.megatron.player.music

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.megatron.player.source.SourceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI wrapper around the service-owned player. Disconnecting a screen never stops background music. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusicPlayback(context: Context) {
    private val app = context.applicationContext
    private val sources = SourceStore(app)
    private val uiPreferences = app.getSharedPreferences("music_ui_v1", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MusicQueueStore(app).read().copy(dismissed = uiPreferences.getBoolean("dismissed", false)))
    val state: StateFlow<MusicPlaybackState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var closed = false
    private var pending: (() -> Unit)? = null
    private var localError: String? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }
    private val ticker = object : Runnable {
        override fun run() { if (!closed) { refresh(); handler.postDelayed(this, 500) } }
    }
    init {
        future.addListener({
            if (!closed) {
                runCatching { future.get() }.onSuccess { connected ->
                    controller = connected; connected.addListener(listener)
                    refresh(); pending?.invoke(); pending = null
                }.onFailure { localError = "音乐播放服务连接失败，请重新打开应用"; _state.value = _state.value.copy(error = localError) }
            }
        }, ContextCompat.getMainExecutor(app))
        handler.post(ticker)
    }
    private fun act(action: (MediaController) -> Unit) {
        if (closed || MusicPlaybackGate.fileOperationActive) return
        check(Looper.myLooper() == Looper.getMainLooper())
        val c = controller
        if (c == null) {
            val generation = MusicPlaybackGate.videoGeneration
            val timerGeneration = com.megatron.player.SleepTimer.shared(app).pauseGeneration
            pending = { if (generation == MusicPlaybackGate.videoGeneration && timerGeneration == com.megatron.player.SleepTimer.shared(app).pauseGeneration) controller?.let(action) }
        } else action(c)
        refresh()
    }
    fun play(tracks: List<MusicTrack>, startId: String) {
        if (tracks.isEmpty()) return
        val requested = tracks.firstOrNull { it.id == startId } ?: return
        if (!available(requested)) return
        val availableSources = sources.list().map { it.id }.toSet()
        val window = musicQueueWindow(tracks.filter { it.sourceId in availableSources }, startId, MusicService.MAX_QUEUE) ?: return
        val list = window.tracks
        val start = window.startIndex
        localError = null
        uiPreferences.edit().putBoolean("dismissed", false).apply()
        act { c -> c.setMediaItems(list.map(MusicTrack::mediaItem), start, 0); c.prepare(); c.play() }
    }
    fun playQueued(id: String) = act { c ->
        val index = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return@act
        if (!available(c.getMediaItemAt(index).musicTrack() ?: return@act)) return@act
        localError = null
        uiPreferences.edit().putBoolean("dismissed", false).apply()
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE || c.playerError != null) c.prepare()
        c.play()
    }
    fun toggle() = act { c ->
        if (c.isPlaying || c.playWhenReady && c.playerError == null) c.pause()
        else {
            val track = c.currentMediaItem?.musicTrack() ?: return@act
            if (!available(track)) return@act
            localError = null
            uiPreferences.edit().putBoolean("dismissed", false).apply()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(c.currentMediaItemIndex)
            if (c.playerError != null || c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }
    fun next() = act { c ->
        localError = null
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        else if (c.mediaItemCount > 0 && c.repeatMode == Player.REPEAT_MODE_ALL) c.seekToDefaultPosition(0)
        if (c.playerError != null) c.prepare()
    }
    fun previous() = act { c ->
        localError = null
        if (c.currentPosition > 3000) c.seekTo(0) else if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
        if (c.playerError != null) c.prepare()
    }
    fun seekTo(position: Long) = act { c ->
        val duration = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.seekTo(position.coerceIn(0, duration))
    }
    fun setMode(mode: MusicMode) = act { c -> c.repeatMode = mode.repeatMode(); c.shuffleModeEnabled = mode == MusicMode.SHUFFLE }
    fun enqueue(track: MusicTrack, next: Boolean) = act { c ->
        if (!available(track)) return@act
        val existing = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == track.id }
        if (existing != null) {
            if (next) {
                localError = null
                val current = c.currentMediaItemIndex
                if (existing != current) c.moveMediaItem(existing, if (existing < current) current else current + 1)
            } else fail("这首歌已在队列中")
            return@act
        }
        if (c.mediaItemCount >= MusicService.MAX_QUEUE) { fail("队列已达 500 首，请先移除部分歌曲"); return@act }
        localError = null
        if (next && c.mediaItemCount > 0) c.addMediaItem(c.currentMediaItemIndex + 1, track.mediaItem()) else c.addMediaItem(track.mediaItem())
    }
    fun enqueue(track: MusicTrack) = enqueue(track, false)
    fun remove(id: String) = act { c ->
        for (i in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(i).mediaId == id) c.removeMediaItem(i)
    }
    /** Finish reconciliation even when the sheet/activity goes away after the remote write. */
    suspend fun mutateFile(repository: MusicRepository, track: MusicTrack, destinationDirectory: String?) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
            check(!MusicPlaybackGate.fileOperationActive) { "正在处理文件，请稍后重试" }
            kotlinx.coroutines.withTimeout(5_000) { while (MusicPlaybackGate.player == null) kotlinx.coroutines.delay(50) }
            val player = MusicPlaybackGate.player ?: error("音乐播放服务未连接")
            MusicPlaybackGate.fileOperationActive = true
            pending = null
            val current = player.currentMediaItem?.mediaId == track.id
            val position = player.currentPosition.coerceAtLeast(0)
            if (current) { player.pause(); player.stop() }
            try {
                val replacement = repository.mutateFile(track, destinationDirectory)
                for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId == track.id) {
                    if (replacement == null) player.removeMediaItem(i) else player.replaceMediaItem(i, replacement.mediaItem())
                }
                if (current && replacement != null) { player.seekTo(position); player.prepare() }
                MusicQueueStore(app).write(player)
                localError = null
            } catch (e: Exception) {
                if (current) player.prepare()
                throw e
            } finally { MusicPlaybackGate.fileOperationActive = false; refresh() }
        }
    fun clear() { localError = null; act { c -> c.stop(); c.clearMediaItems() } }
    /** User dismissal stops audio and hides the UI while keeping the queue and position. */
    fun stopAndDismiss() {
        pending = null
        localError = null
        uiPreferences.edit().putBoolean("dismissed", true).apply()
        act { it.stop() }
        _state.value = _state.value.copy(isPlaying = false, dismissed = true, error = null)
    }
    fun pauseForVideo() { pending = null; MusicPlaybackGate.pauseForVideo(); refresh() }
    fun close() {
        if (closed) return
        closed = true; pending = null; handler.removeCallbacks(ticker)
        controller?.removeListener(listener); controller = null
        MediaController.releaseFuture(future)
    }
    private fun available(track: MusicTrack): Boolean {
        if (!MusicRepository.shared(app).fileVisible(track.sourceId, track.path)) {
            fail("文件已删除或移动，请刷新目录")
            return false
        }
        if (sources.find(track.sourceId) != null) return true
        fail("音乐来源已删除，请在音乐目录管理中重新选择 NAS 连接")
        return false
    }
    private fun fail(message: String) { localError = message; _state.value = _state.value.copy(error = message) }
    private fun refresh() {
        val c = controller ?: return
        val repository = MusicRepository.shared(app)
        val queue = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).musicTrack() }.map(repository::displayTrack)
        val metadata = c.mediaMetadata
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val current = c.currentMediaItem?.musicTrack()?.let { track -> track.copy(
            title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: track.title,
            artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: track.artist,
            album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: track.album,
            durationMs = duration.takeIf { it > 0 } ?: track.durationMs,
        ) }
        val displayed = current?.let { repository.rawTrack(it.id)?.let(repository::displayTrack) ?: repository.displayTrack(it) }
        _state.value = MusicPlaybackState(displayed, queue, c.currentPosition.coerceAtLeast(0), duration.takeIf { it > 0 } ?: current?.durationMs ?: 0,
            c.isPlaying, c.musicMode(), localError ?: c.playerError?.let(::musicPlaybackError), true,
            c.sessionExtras.getString("music_output_info").orEmpty(), uiPreferences.getBoolean("dismissed", false))
    }
}
private fun musicPlaybackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "NAS 连接中断，请检查网络后点击重试"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "NAS 无法读取歌曲，请检查连接、账号权限及文件是否仍存在"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "这首歌的封装或编码暂不支持，可选择其他版本"
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "歌曲解码失败，请检查文件或切换其他版本"
    else -> "歌曲无法播放，请检查 NAS 连接及文件后点击重试"
}
