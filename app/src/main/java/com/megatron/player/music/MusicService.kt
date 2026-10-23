package com.megatron.player.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.decoder.ffmpeg.ApeExtractor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.megatron.player.MainActivity
import com.megatron.player.source.AppDataSourceFactory
import com.megatron.player.source.SourceStore
import com.megatron.player.source.SourceUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** In-process synchronous gate: video cannot begin before an active music player has paused. */
object MusicPlaybackGate {
    @Volatile var fileOperationActive = false
    internal var player: Player? = null
    internal var videoGeneration: Long = 0
    fun pauseForVideo() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback ownership must be changed on the main thread" }
        videoGeneration++
        player?.pause()
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusicService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var saved: MusicQueueStore
    private val handler = Handler(Looper.getMainLooper())
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var metadataJob: Job? = null
    private var lastMetadataTrackId: String? = null
    private lateinit var repository: MusicRepository
    private lateinit var audioOutput: MusicAudioOutput
    private var lastOutputInfo: String? = null
    private val checkpoint = object : Runnable {
        override fun run() { save(); handler.postDelayed(this, 3_000) }
    }
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider(this).apply {
            setSmallIcon(com.megatron.player.R.drawable.ic_brand_notification)
        })
        saved = MusicQueueStore(this)
        repository = MusicRepository.shared(this)
        val sources = SourceStore(this)
        val standard = DefaultExtractorsFactory()
        val extractors = ExtractorsFactory { arrayOf(ApeExtractor(), *standard.createExtractors()) }
        val mediaSources = DefaultMediaSourceFactory(AppDataSourceFactory(this, sources::credentials), extractors)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(2) {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (info.errorCount > 2) C.TIME_UNSET else super.getRetryDelayMsFor(info)
            })
        audioOutput = MusicAudioOutput(this)
        player = ExoPlayer.Builder(this, audioOutput.factory(this))
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioOutput.decoderName = decoderName
                publishOutputInfo()
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) { publishOutputInfo() }
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { publishOutputInfo() }
        })
        com.megatron.player.SleepTimer.shared(this).register(this) { player.pause(); save() }
        MusicPlaybackGate.player = player
        val prior = saved.read()
        if (prior.queue.isNotEmpty()) {
            player.setMediaItems(prior.queue.map(MusicTrack::mediaItem),
                prior.queue.indexOfFirst { it.id == prior.current?.id }.coerceAtLeast(0), prior.positionMs.coerceAtLeast(0))
            player.repeatMode = prior.mode.repeatMode()
            player.shuffleModeEnabled = prior.mode == MusicMode.SHUFFLE
            // Restoring state is deliberately silent; a user transport action starts preparation.
            player.playWhenReady = false
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) getSharedPreferences("music_ui_v1", Context.MODE_PRIVATE).edit().putBoolean("dismissed", false).apply()
            }
            override fun onEvents(player: Player, events: Player.Events) { save() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != lastMetadataTrackId) {
                    lastMetadataTrackId = mediaItem?.mediaId
                    metadataJob?.cancel()
                    if (player.playbackState == Player.STATE_READY) handler.post { enrichCurrent() }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) enrichCurrent() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (player.playbackState == Player.STATE_READY) handler.post { enrichCurrent() }
            }
            override fun onPlayerError(error: PlaybackException) { player.pause(); save() }
        })
        val launch = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val activity = PendingIntent.getActivity(this, 31, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int =
                    if (MusicPlaybackGate.fileOperationActive) androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE else androidx.media3.session.SessionResult.RESULT_SUCCESS
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    if (controller.packageName != packageName) builder.setAvailablePlayerCommands(
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build())
                    return builder.build()
                }
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    if (controller.packageName != packageName || mediaItems.size > MAX_QUEUE) return Futures.immediateFailedFuture(IllegalArgumentException("不支持的音乐队列"))
                    val accepted = mediaItems.mapNotNull { item ->
                        val track = item.musicTrack() ?: return@mapNotNull null
                        if (musicId(track.sourceId, track.path) != track.id || !isMusicFile(track.path) || sources.find(track.sourceId) == null || !repository.fileVisible(track.sourceId, track.path)) null else track.mediaItem()
                    }
                    return if (accepted.size == mediaItems.size) Futures.immediateFuture(accepted)
                    else Futures.immediateFailedFuture(IllegalArgumentException("音乐来源已删除或文件无效，请重新选择 NAS"))
                }
            }).build()
        metadataScope.launch {
            repository.supplementRevision.collect {
                for (i in 0 until player.mediaItemCount) {
                    val before = player.getMediaItemAt(i).musicTrack() ?: continue
                    val raw = repository.rawTrack(before.id) ?: continue
                    val updated = repository.displayTrack(raw)
                    if (before != updated) player.replaceMediaItem(i, updated.mediaItem())
                }
                save()
            }
        }
        handler.postDelayed(checkpoint, 3_000)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }
    private fun save() { if (::player.isInitialized) { saved.write(player); publishOutputInfo() } }
    private fun publishOutputInfo() {
        val currentSession = session ?: return
        val info = audioOutput.describe(player)
        if (info != lastOutputInfo) {
            lastOutputInfo = info
            currentSession.setSessionExtras(Bundle().apply { putString("music_output_info", info) })
        }
    }
    private fun enrichCurrent() {
        val before = player.currentMediaItem?.musicTrack() ?: return
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        val format = player.audioFormat
        val bits = when (format?.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32; else -> before.bitDepth
        }
        val decodedMetadata = player.mediaMetadata
        metadataJob?.cancel()
        metadataJob = metadataScope.launch {
          try {
            val existing = repository.tracks.value.firstOrNull { it.id == before.id } ?: before
            val tagged = if (musicNeedsMetadata(existing)) repository.enrich(existing) else existing
            val enriched = repository.updatePlaybackInfo(tagged, duration, format?.sampleRate ?: 0, bits,
                decodedMetadata.title?.toString(), decodedMetadata.artist?.toString(), decodedMetadata.albumTitle?.toString(),
                decodedMetadata.artworkData?.takeIf { it.size <= MusicTagParser.MAX_ART })
            val current = player.currentMediaItem
            if (current?.mediaId == enriched.id && current.musicTrack() != enriched) {
                player.replaceMediaItem(player.currentMediaItemIndex, enriched.mediaItem())
            }
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { /* Optional tags never interrupt playback. */ }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(checkpoint)
        metadataScope.cancel()
        com.megatron.player.SleepTimer.shared(this).unregister(this)
        if (::player.isInitialized) {
            save()
            if (MusicPlaybackGate.player === player) MusicPlaybackGate.player = null
            session?.release(); session = null
            player.release()
        }
        super.onDestroy()
    }
    companion object { const val MAX_QUEUE = 500 }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun MusicTrack.mediaItem(): MediaItem {
    val extra = android.os.Bundle().apply { putString("music_track", json().toString()) }
    val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist.ifBlank { null }).setAlbumTitle(album.ifBlank { null })
        .setTrackNumber(trackNumber.takeIf { it > 0 }).setDiscNumber(discNumber.takeIf { it > 0 }).setExtras(extra)
        .setArtworkUri(artworkPath?.let { android.net.Uri.fromFile(java.io.File(it)) })
        .build()
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMimeType(musicContainerMimeType(path)).setMediaMetadata(metadata).build()
}
internal fun MediaItem.musicTrack(): MusicTrack? = mediaMetadata.extras?.getString("music_track")?.let {
    runCatching { musicTrack(JSONObject(it)) }.getOrNull()
}
internal fun MusicMode.repeatMode(): Int = when (this) { MusicMode.ONE -> Player.REPEAT_MODE_ONE; MusicMode.LOOP, MusicMode.SHUFFLE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
internal fun Player.musicMode(): MusicMode = when { shuffleModeEnabled -> MusicMode.SHUFFLE; repeatMode == Player.REPEAT_MODE_ONE -> MusicMode.ONE; repeatMode == Player.REPEAT_MODE_ALL -> MusicMode.LOOP; else -> MusicMode.SEQUENCE }

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MusicQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("music_playback_v1", Context.MODE_PRIVATE)
    private val queuePrefs = context.applicationContext.getSharedPreferences("music_queue_v1", Context.MODE_PRIVATE)
    private var lastTimeline: androidx.media3.common.Timeline? = null
    fun read(): MusicPlaybackState = runCatching {
        val legacy = JSONObject(prefs.getString("queue", "{}") ?: "{}")
        val a = queuePrefs.getString("items", null)?.let(::JSONArray) ?: legacy.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until minOf(a.length(), MusicService.MAX_QUEUE)).mapNotNull { runCatching { musicTrack(a.getJSONObject(it)) }.getOrNull() }
        val current = prefs.getString("current", null) ?: legacy.optString("current")
        MusicPlaybackState(current = tracks.firstOrNull { it.id == current } ?: tracks.firstOrNull(), queue = tracks,
            positionMs = prefs.getLong("position", legacy.optLong("position")).coerceAtLeast(0),
            mode = runCatching { MusicMode.valueOf(prefs.getString("mode", null) ?: legacy.optString("mode")) }.getOrDefault(MusicMode.SEQUENCE))
    }.getOrDefault(MusicPlaybackState())
    fun write(player: Player) {
        if (com.megatron.player.backup.BackupRestoreGuard.restoring) return
        // Queue metadata and the frequent progress checkpoint use separate preference files.
        // A 500-song queue is therefore not re-encoded/re-written every three seconds.
        val timeline = player.currentTimeline
        if (lastTimeline !== timeline) {
            val queue = (0 until minOf(player.mediaItemCount, MusicService.MAX_QUEUE)).mapNotNull { player.getMediaItemAt(it).musicTrack() }
            queuePrefs.edit().putString("items", JSONArray(queue.map { it.json() }).toString()).apply()
            lastTimeline = timeline
        }
        prefs.edit().remove("queue").putString("current", player.currentMediaItem?.mediaId ?: "")
            .putLong("position", player.currentPosition.coerceAtLeast(0)).putString("mode", player.musicMode().name).apply()
    }
}
