package com.megatron.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.megatron.player.source.AppDataSourceFactory
import com.megatron.player.source.SourceStore
import com.megatron.player.plex.PlexDataSourceFactory
import com.megatron.player.plex.PlexProgressReporter
import com.megatron.player.plex.PlexStore
import com.megatron.player.plex.PlexUris
import com.megatron.player.plex.PlexPlaybackSession
import com.megatron.player.plex.plexPlaybackFailure
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.megatron.player.source.SourceUris
import com.megatron.player.source.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerSession(
    private val context: Context,
    private val sourceUri: Uri,
    private val title: String,
    var subtitleUri: Uri?,
    subtitleName: String? = subtitleUri?.lastPathSegment,
    private val onStateChanged: (PlayerSession) -> Unit = {},
) {
    val trackSelector = DefaultTrackSelector(context)
    private val plexStore = PlexStore(context)
    private val sourceStore = SourceStore(context)
    private val plexWebDavStore = com.megatron.player.plex.PlexWebDavBindingStore(context)
    private val playbackPreferences = PlaybackPreferencesStore(context)
    private val plexPlaybackSession = PlexPlaybackSession(plexStore.clientIdentifier,
        learnedPaths = plexWebDavStore::learned, rememberPath = plexWebDavStore::remember)
    private var plexReporter = PlexUris.parse(sourceUri)?.let { PlexProgressReporter(plexStore, it, plexPlaybackSession.sessionIdentifier, title = title) }
    var progressDelegatedToCast = false
        private set
    private val episodeSkip = EpisodeSkipPolicy()
    private var effectiveEpisodeSkip: EpisodeSkipTimes? = null
    private val audioSyncController = AudioSyncController(context, sourceUri)
    private val subtitleTiming = SubtitleTiming(context, sourceUri)
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var subtitleCall: okhttp3.Call? = null
    private var subtitleDiscoveryJob: kotlinx.coroutines.Job? = null
    @Volatile private var subtitleDiscoveryGeneration = 0
    private var subtitleDiscoveryStarted = false
    var nasSubtitles: List<NasSubtitle> = emptyList()
        private set
    var subtitleDiscoveryStatus: String? = null
        private set
    @Volatile private var readStage = "等待媒体读取"
    private val qualityInfo = VideoQualityInfo()
    private val videoReads = VideoReadMetrics(sourceUri)
    private val renderersFactory = AudioSyncRenderersFactory(context, audioSyncController)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    val player: ExoPlayer = ExoPlayer.Builder(context, subtitleTimingFactory(renderersFactory, subtitleTiming))
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(videoReads.wrap(PlexDataSourceFactory(
            AppDataSourceFactory(context, sourceStore::credentials), plexStore::credentials, plexStore.clientIdentifier,
            onReadStage = { readStage = it }, playbackSession = plexPlaybackSession,
            webDavBinding = plexWebDavStore::read, sourceCredentials = sourceStore::credentials)), VideoExtractorsFactory())
            .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (loadErrorInfo.exception.plexPlaybackFailure() != null) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
            }))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    var lastError: PlaybackException? = null
        private set
    var completed = false
        private set
    private var lastSavedAt = 0L
    private var lastPlexReportAt = 0L
    private var currentSubtitleName: String? = subtitleName
    private var released = false
    private var prepared = false
    private var audioDecoderName: String? = null
    private var videoDecoderName: String? = null
    private var lastAppliedTrackParameters: TrackSelectionParameters? = null
    private var lastAppliedPreferences: PlaybackPreferences? = null
    private var readyForManualTrackChanges = false
    private var pendingExternalSubtitleSelection = subtitleUri != null
    private val sessionTrackChanges = mutableListOf<TrackSelectionParameters>()

    init {
        SleepTimer.shared(context).register(this) { if (!released) { player.pause(); saveIfNeeded(force = true) } }
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                selectPendingExternalSubtitle(tracks)
                onStateChanged(this@PlayerSession)
            }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                val previous = lastAppliedTrackParameters
                val sessionChange = sessionTrackChanges.indexOfFirst { it == parameters }.let { index ->
                    if (index < 0) false else { sessionTrackChanges.removeAt(index); true }
                }
                if (!sessionChange && readyForManualTrackChanges && previous != null && parameters != previous) {
                    if (overrideForType(parameters, C.TRACK_TYPE_TEXT) != overrideForType(previous, C.TRACK_TYPE_TEXT) ||
                        parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) != previous.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                        pendingExternalSubtitleSelection = false
                    }
                    rememberManualTrackChoice(parameters, previous)
                }
                lastAppliedTrackParameters = parameters
            }
            override fun onPlayerError(error: PlaybackException) {
                lastError = error
                onStateChanged(this@PlayerSession)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                qualityInfo.playbackState(playbackState)
                completed = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    readyForManualTrackChanges = true
                    applyEpisodeSkips()
                    discoverNasSubtitles()
                }
                onStateChanged(this@PlayerSession)
                saveIfNeeded(force = playbackState == Player.STATE_ENDED)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                applyEpisodeSkips()
                onStateChanged(this@PlayerSession)
                saveIfNeeded(force = !isPlaying)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                // This is the selected decoder input format. Analytics delivery is asynchronous;
                // if restoring a saved value changes the offset, the in-place refresh below
                // flushes any audio that may already have reached the sink.
                val previousOffset = audioOffsetMs
                val changed = audioSyncController.updateAudioFormat(format)
                if (changed && previousOffset != audioOffsetMs) refreshAudioSyncAtCurrentPosition()
                onStateChanged(this@PlayerSession)
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoderName = decoderName
                onStateChanged(this@PlayerSession)
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                qualityInfo.inputFormat = format
                onStateChanged(this@PlayerSession)
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoderName = decoderName
                qualityInfo.decoder(decoderName)
                onStateChanged(this@PlayerSession)
            }
        })
    }

    fun prepare(resumeMs: Long, playWhenReady: Boolean = true) {
        prepared = false
        readyForManualTrackChanges = false
        pendingExternalSubtitleSelection = subtitleUri != null
        applyPlaybackPreferences()
        player.setMediaItem(buildMediaItem())
        player.seekTo(resumeMs.coerceAtLeast(0L))
        prepared = true
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /** Applies language and subtitle preferences to this session only. */
    fun applyPlaybackPreferences() {
        val preferences = playbackPreferences.read()
        val previousPreferences = lastAppliedPreferences
        val builder = trackSelector.parameters.buildUpon()
        if (previousPreferences != null && previousPreferences.audioLanguage != preferences.audioLanguage) builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (previousPreferences != null && previousPreferences.subtitleLanguage != preferences.subtitleLanguage) builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val parameters = builder
            .setPreferredAudioLanguages(*(if (preferences.audioLanguage.isBlank()) emptyArray() else arrayOf(preferences.audioLanguage)))
            .setPreferredTextLanguages(*(if (preferences.subtitleLanguage.isBlank()) emptyArray() else arrayOf(preferences.subtitleLanguage)))
            .setSelectTextByDefault(preferences.subtitlesEnabled && preferences.subtitleLanguage.isNotBlank())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !preferences.subtitlesEnabled)
            .build()
        lastAppliedPreferences = preferences
        applySessionTrackParameters(parameters)
    }

    fun subtitleTextSizeFraction(): Float = playbackPreferences.read().subtitleSize.fraction
    val externalSubtitleName: String? get() = currentSubtitleName
    val subtitleOffsetMs: Int get() = subtitleTiming.offsetMs

    fun setSubtitleOffsetMs(value: Int) {
        if (released) return
        subtitleTiming.set(value)
        // Reprepare clears already-discarded cues when the text clock is moved backwards.
        refreshAudioSyncAtCurrentPosition()
        onStateChanged(this)
    }

    fun discoverNasSubtitles(force: Boolean = false) {
        if (released || (subtitleDiscoveryStarted && !force)) return
        if (sourceUri.scheme !in setOf(SourceUris.SCHEME, "mpplex")) return
        subtitleDiscoveryStarted = true
        val generation = ++subtitleDiscoveryGeneration
        subtitleCall?.cancel()
        subtitleDiscoveryJob?.cancel()
        subtitleDiscoveryStatus = "正在查找同目录字幕…"
        onStateChanged(this)
        subtitleDiscoveryJob = subtitleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val direct = SourceUris.parse(sourceUri)
                    val location = if (direct != null) direct else {
                        val ref = PlexUris.parse(sourceUri) ?: return@withContext null
                        val binding = plexWebDavStore.read(ref.serverId) ?: return@withContext null
                        val plex = plexStore.credentials(ref.serverId) ?: return@withContext null
                        // READY means the video session has already resolved and cached this path.
                        val path = plexPlaybackSession.prepareWebDav(plex, ref, binding, sourceStore.credentials(binding.sourceId)) {
                            if (released || generation != subtitleDiscoveryGeneration) it.cancel() else subtitleCall = it
                        }
                        binding.sourceId to path
                    }
                    val credentials = sourceStore.credentials(location.first) ?: return@withContext null
                    val siblings = WebDavClient().list(credentials, location.second.substringBeforeLast('/', "")) {
                        if (released || generation != subtitleDiscoveryGeneration) it.cancel() else subtitleCall = it
                    }
                    sameNameSubtitles(location.second, siblings).map { nasSubtitle(location.first, it) }
                }
            }
            if (!released && generation == subtitleDiscoveryGeneration) {
                subtitleCall = null
                nasSubtitles = result.getOrNull().orEmpty()
                subtitleDiscoveryStatus = when {
                    result.isFailure -> "未能读取同目录字幕，可重试或手动选择；视频播放不受影响。"
                    result.getOrNull() == null -> "当前片源不支持同目录字幕发现，可手动选择。"
                    nasSubtitles.isEmpty() -> "同目录未找到同名字幕，可手动选择。"
                    else -> "已找到 ${nasSubtitles.size} 个同名字幕"
                }
                onStateChanged(this@PlayerSession)
            }
        }
    }

    val audioOffsetMs: Int get() = audioSyncController.audioOffsetMs
    val audioSyncRemembered: Boolean get() = audioSyncController.audioSyncRemembered

    /** Sets the offset in the range [-2000, 2000]. Positive values delay audio. */
    fun setAudioOffsetMs(value: Int) {
        if (released) return
        audioSyncController.setAudioOffsetMs(value)
        refreshAudioSyncAtCurrentPosition()
        onStateChanged(this)
    }

    fun setAudioSyncRemembered(remember: Boolean) {
        if (released) return
        audioSyncController.setAudioSyncRemembered(remember)
        onStateChanged(this)
    }

    fun audioSyncTrackLabel(): String = audioSyncController.audioSyncTrackLabel()

    private fun refreshAudioSyncAtCurrentPosition() {
        if (!prepared || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        // A same-position seek may be optimized away by ExoPlayer. Stop/prepare forces the
        // output chain to flush while retaining the same media item, position and track rules.
        player.stop()
        player.seekTo(position)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun rememberManualTrackChoice(parameters: TrackSelectionParameters, previous: TrackSelectionParameters) {
        val preferences = playbackPreferences.read()
        val audioChanged = overrideForType(parameters, C.TRACK_TYPE_AUDIO) != overrideForType(previous, C.TRACK_TYPE_AUDIO)
        val textChanged = overrideForType(parameters, C.TRACK_TYPE_TEXT) != overrideForType(previous, C.TRACK_TYPE_TEXT)
        val textDisabledChanged = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) != previous.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val audio = if (audioChanged) overrideLanguage(parameters, C.TRACK_TYPE_AUDIO) else null
        val text = if (textChanged) overrideLanguage(parameters, C.TRACK_TYPE_TEXT) else null
        if (audioChanged || textChanged || textDisabledChanged) playbackPreferences.save(preferences.copy(
            audioLanguage = audio?.takeUnless { it == "und" }.orEmpty().ifBlank { preferences.audioLanguage },
            subtitleLanguage = text?.takeUnless { it == "und" }.orEmpty().ifBlank { preferences.subtitleLanguage },
            subtitlesEnabled = if (textDisabledChanged) !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) else preferences.subtitlesEnabled,
        ))
    }

    private fun overrideForType(parameters: TrackSelectionParameters, type: Int) = parameters.overrides.values.firstOrNull { it.type == type }

    private fun overrideLanguage(parameters: TrackSelectionParameters, type: Int): String? {
        val override = overrideForType(parameters, type) ?: return null
        val index = override.trackIndices.firstOrNull() ?: return null
        return override.mediaTrackGroup.getFormat(index).language?.takeIf { it.isNotBlank() }
    }

    /** Update the baseline before notifying Media3: choosing this file must not rewrite language defaults. */
    private fun applySessionTrackParameters(parameters: TrackSelectionParameters) {
        if (trackSelector.parameters != parameters) sessionTrackChanges += parameters
        lastAppliedTrackParameters = parameters
        trackSelector.setParameters(parameters)
    }

    private fun selectPendingExternalSubtitle(tracks: Tracks): Boolean {
        if (!pendingExternalSubtitleSelection) return false
        val uri = subtitleUri ?: return false
        val expectedId = externalSubtitleTrackId(uri.toString())
        for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                if (group.isTrackSupported(index) && isExternalSubtitleFormat(format.id, format.label, expectedId, currentSubtitleName)) {
                    pendingExternalSubtitleSelection = false
                    applySessionTrackParameters(trackSelector.parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build())
                    return true
                }
            }
        }
        return false
    }

    fun isExternalSubtitleSelected(uri: Uri): Boolean {
        if (uri != subtitleUri || player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false
        val expectedId = externalSubtitleTrackId(uri.toString())
        return player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { index ->
                val format = group.getTrackFormat(index)
                group.isTrackSelected(index) && isExternalSubtitleFormat(format.id, format.label, expectedId, currentSubtitleName)
            }
        }
    }

    private fun buildMediaItem(): MediaItem {
        val itemBuilder = MediaItem.Builder().setUri(sourceUri)
        subtitleUri?.let { uri ->
            subtitleMimeType(currentSubtitleName)?.let { mime ->
                itemBuilder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime).setLanguage("und")
                    .setLabel(currentSubtitleName)
                    .setId(externalSubtitleTrackId(uri.toString()))
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
        }
        return itemBuilder.build()
    }

    fun replaceSubtitle(uri: Uri?, name: String?) {
        if (released) return
        if (uri != null && uri == subtitleUri && name == currentSubtitleName && lastError == null) {
            pendingExternalSubtitleSelection = true
            if (selectPendingExternalSubtitle(player.currentTracks)) {
                onStateChanged(this)
                return
            }
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        subtitleUri = uri
        currentSubtitleName = name
        pendingExternalSubtitleSelection = uri != null
        val parameters = trackSelector.parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (uri != null) parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setSelectTextByDefault(true)
        else {
            val preferences = playbackPreferences.read()
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !preferences.subtitlesEnabled)
                .setSelectTextByDefault(preferences.subtitlesEnabled && preferences.subtitleLanguage.isNotBlank())
        }
        applySessionTrackParameters(parameters.build())
        lastError = null
        player.setMediaItem(buildMediaItem(), position)
        player.prepare()
        player.playWhenReady = wasPlaying
        onStateChanged(this)
    }

    fun retry() {
        lastError = null
        plexPlaybackSession.reset()
        player.prepare()
        player.playWhenReady = true
        onStateChanged(this)
    }

    fun saveIfNeeded(force: Boolean = false) {
        if (released || !prepared || progressDelegatedToCast) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt < 5_000L) return
        val duration = player.duration
        val position = player.currentPosition.coerceAtLeast(0L)
        val history = PlaybackHistory(context)
        val old = history.find(sourceUri.toString())
        history.save(RecentVideo(uri = sourceUri.toString(), title = title, positionMs = position,
            durationMs = if (duration == C.TIME_UNSET) old?.durationMs ?: 0L else duration.coerceAtLeast(0L),
            subtitleUri = subtitleUri?.toString(),
            subtitleName = currentSubtitleName,
            updatedAt = System.currentTimeMillis()))
        if (duration > 0 && player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) && (force || now - lastPlexReportAt >= 10_000L)) {
            plexReporter?.offer(if (completed) "stopped" else if (player.isPlaying) "playing" else "paused", position, duration, completed)
            lastPlexReportAt = now
        }
        lastSavedAt = now
    }

    fun configureEpisodeSkipping(isEpisode: Boolean, exactStart: Boolean, seriesKey: SeriesSkipKey? = null) {
        if (isEpisode && effectiveEpisodeSkip == null) effectiveEpisodeSkip = SeriesSkipPreferences(context).resolve(seriesKey)
        episodeSkip.configure(isEpisode, exactStart)
        applyEpisodeSkips()
    }

    fun applyEpisodeSkips() {
        if (released || !prepared || progressDelegatedToCast || player.playbackState != Player.STATE_READY) return
        val times = effectiveEpisodeSkip ?: return
        val action = episodeSkip.next(player.currentPosition, player.duration, canSeek(), player.isPlaying,
            times.introSeconds, times.outroSeconds) ?: return
        player.seekTo(action.positionMs)
        onStateChanged(this)
    }

    fun canSeek(): Boolean = player.isCurrentMediaItemSeekable && player.duration > 0L

    fun delegateProgressToCast() {
        if (progressDelegatedToCast || released) return
        saveIfNeeded(force = true)
        plexReporter?.finish(player.currentPosition.coerceAtLeast(0), player.duration, false)
        plexReporter = null
        progressDelegatedToCast = true
        player.pause()
    }

    fun reclaimProgressFromCast(positionMs: Long) {
        if (!progressDelegatedToCast || released) return
        progressDelegatedToCast = false
        plexReporter = PlexUris.parse(sourceUri)?.let { PlexProgressReporter(plexStore, it, title = title) }
        player.seekTo(positionMs.coerceAtLeast(0))
        player.pause()
        saveIfNeeded(force = true)
    }

    fun seekToPosition(positionMs: Long): Boolean {
        if (released || !canSeek() || positionMs !in 0..player.duration) return false
        val skip = effectiveEpisodeSkip ?: EpisodeSkipTimes(0, 0)
        episodeSkip.manualSeek(positionMs, player.duration, skip.introSeconds, skip.outroSeconds)
        player.seekTo(positionMs)
        saveIfNeeded(force = true)
        onStateChanged(this)
        return true
    }

    fun mediaVolumeInfo(): String {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        return "${audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
    }

    fun sampleVideoQuality() { if (!released) qualityInfo.sample(player) }
    fun updateDisplayInfo(view: android.view.View) { qualityInfo.display(view) }
    private fun sourceDescription(): String = when (sourceUri.scheme) {
        "mpplex" -> "${plexPlaybackSession.videoSource} · 原文件直读（无转码）"
        "mpdav" -> "WebDAV · 原文件直读（无转码）"
        "file", "content" -> "本地文件直读"
        "http", "https" -> "HTTP(S)链接 · 上游是否转码未确认"
        else -> "未报告"
    }
    fun diagnostics(): String {
        val cast = com.megatron.player.cast.CastController.shared(context).state.value
        if (progressDelegatedToCast && cast.uri == sourceUri.toString()) return buildString {
            append("NASPlayer ${BuildConfig.VERSION_NAME}\n【电视投屏】\n")
            append("设备：${cast.device} · ${cast.protocol}\n视频通道：原文件字节转发（无转码）\n")
            append("播放进度：${formatPlaybackTime(cast.positionMs)} / ${if (cast.durationMs > 0) formatPlaybackTime(cast.durationMs) else "未知"}\n")
            append("电视视频解码器：未报告\n电视HDR实际输出：未确认\n电视缓冲与丢帧：未报告\n")
            append("电视跳转：${if (cast.canSeek) "支持" else "未报告支持"}\n错误：${cast.error ?: "无"}")
        }

        val format = player.videoFormat
        val audio = player.audioFormat
        val subtitle = currentSubtitleName ?: "无"
        val error = lastError?.errorCodeName ?: "无"
        return buildString {
            append("Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n")
            append("NASPlayer ${BuildConfig.VERSION_NAME} / Media3 1.9.1\n")
            append("播放进度：${formatPlaybackTime(player.currentPosition)} / ${if (player.duration > 0) formatPlaybackTime(player.duration) else "未知"}\n")
            append("文件跳转：${if (player.isCurrentMediaItemSeekable) "支持" else "未就绪或不支持"} · 实时流：${if (player.isCurrentMediaItemLive) "是" else "否"}\n")
            append("音量：播放器 ${(player.volume * 100).toInt()}% · 系统媒体 ${mediaVolumeInfo()}\n")
            append("FFmpeg 音频扩展：${FfmpegLibrary.getVersion() ?: "未加载"}\n")
            append("\n${qualityInfo.text(player, sourceDescription(), videoReads)}\n【音频】\n")
            append("当前音频：${audio?.sampleMimeType ?: "未选中"} ${audio?.codecs ?: ""}\n")
            append("音频解码器：${audioDecoderName ?: "未启用"}\n")
            append("音画偏移：${audioSyncOffsetText(audioOffsetMs)} · 记忆：${if (audioSyncRemembered) "开启" else "关闭"} · 音轨：${audioSyncTrackLabel()}\n")
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            var number = 0
            for (group in groups) {
                for (index in 0 until group.length) {
                    val track = group.getTrackFormat(index)
                    val selected = if (group.isTrackSelected(index)) "已选" else "未选"
                    val support = if (group.isTrackSupported(index)) "支持" else "不支持(${group.getTrackSupport(index)})"
                    append("音轨 ${++number}：${track.sampleMimeType ?: "未知"} ${track.codecs ?: ""} ")
                    append("${track.channelCount} 声道 ${track.sampleRate}Hz ${track.language ?: ""} [$selected/$support]\n")
                }
            }
            append("外挂字幕：$subtitle · ${subtitleOffsetText(subtitleOffsetMs)}\n错误：$error")
            if (sourceUri.scheme == "mpplex") {
                append("\n视频来源：${plexPlaybackSession.videoSource}")
                append("\n读取阶段：$readStage")
                plexPlaybackSession.decision?.let { append("\nPlex播放决策：${it.diagnosticText()}") }
            }
            lastError?.let { append("\n异常链：\n${playbackFailureCauses(it)}") }
            plexReporter?.let {
                append("\nPlex进度：${it.status}")
                com.megatron.player.plex.PlexRequestDiagnostics.snapshot().takeIf { it.isNotEmpty() }?.let { trace ->
                    append("\n最近 Plex 请求：\n$trace")
                }
            }
        }
    }

    fun audioNotice(): String? {
        if (player.playbackState != Player.STATE_READY) return null
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isNotEmpty() && groups.none { it.isSupported }) {
            return "文件音轨暂不受支持。请复制播放信息，里面包含原始音轨编码。"
        }
        return null
    }

    fun release() {
        if (released) return
        saveIfNeeded(force = true)
        plexReporter?.finish(player.currentPosition.coerceAtLeast(0L), player.duration, completed)
        SleepTimer.shared(context).unregister(this)
        released = true
        subtitleCall?.cancel()
        subtitleScope.cancel()
        plexPlaybackSession.release()
        player.release()
    }

    fun playbackErrorHint(): String? = lastError?.plexPlaybackFailure()?.description()?.let {
        if (it.length <= 280) it else it.take(280) + "…（完整说明见播放信息）"
    }

    fun failureGuide() = playbackFailureGuide(lastError, readStage)
}
