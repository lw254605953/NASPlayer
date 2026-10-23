package com.megatron.player

import android.content.Context
import androidx.media3.ui.SubtitleView

enum class PlaybackLanguage(val code: String, val label: String) {
    AUTO("", "自动"), CHINESE("zh", "中文"), ENGLISH("en", "英语"), JAPANESE("ja", "日语"), KOREAN("ko", "韩语")
}

enum class SubtitleSize(val label: String, val fraction: Float) {
    SMALL("小", 0.035f), STANDARD("标准", SubtitleView.DEFAULT_TEXT_SIZE_FRACTION), LARGE("大", 0.065f), EXTRA_LARGE("特大", 0.085f)
}

data class PlaybackPreferences(
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val subtitlesEnabled: Boolean = true,
    val subtitleSize: SubtitleSize = SubtitleSize.STANDARD,
    val episodeIntroSeconds: Int = 0,
    val episodeOutroSeconds: Int = 0,
)

class PlaybackPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_preferences_v1", Context.MODE_PRIVATE)

    fun read(): PlaybackPreferences {
        val size = preferences.getString(KEY_SUBTITLE_SIZE, SubtitleSize.STANDARD.name).orEmpty()
        return PlaybackPreferences(
            audioLanguage = preferences.getString(KEY_AUDIO_LANGUAGE, "").orEmpty(),
            subtitleLanguage = preferences.getString(KEY_SUBTITLE_LANGUAGE, "").orEmpty(),
            subtitlesEnabled = preferences.getBoolean(KEY_SUBTITLES_ENABLED, true),
            episodeIntroSeconds = preferences.getInt(KEY_EPISODE_INTRO, 0).coerceIn(0, 3600),
            episodeOutroSeconds = preferences.getInt(KEY_EPISODE_OUTRO, 0).coerceIn(0, 3600),
            subtitleSize = runCatching { SubtitleSize.valueOf(size) }.getOrDefault(SubtitleSize.STANDARD),
        )
    }

    fun save(value: PlaybackPreferences) {
        preferences.edit()
            .putString(KEY_AUDIO_LANGUAGE, value.audioLanguage)
            .putString(KEY_SUBTITLE_LANGUAGE, value.subtitleLanguage)
            .putBoolean(KEY_SUBTITLES_ENABLED, value.subtitlesEnabled)
            .putString(KEY_SUBTITLE_SIZE, value.subtitleSize.name)
            .putInt(KEY_EPISODE_INTRO, value.episodeIntroSeconds.coerceIn(0, 3600))
            .putInt(KEY_EPISODE_OUTRO, value.episodeOutroSeconds.coerceIn(0, 3600))
            .apply()
    }

    fun saveAudioLanguage(language: String?) = save(read().copy(audioLanguage = language.orEmpty()))
    fun saveSubtitleLanguage(language: String?) = save(read().copy(subtitleLanguage = language.orEmpty()))
    fun saveSubtitlesEnabled(enabled: Boolean) = save(read().copy(subtitlesEnabled = enabled))
    fun saveSubtitleSize(size: SubtitleSize) = save(read().copy(subtitleSize = size))

    companion object {
        private const val KEY_EPISODE_INTRO = "episode_intro_seconds"
        private const val KEY_EPISODE_OUTRO = "episode_outro_seconds"
        private const val KEY_AUDIO_LANGUAGE = "audio_language"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
        private const val KEY_SUBTITLE_SIZE = "subtitle_size"
    }
}
