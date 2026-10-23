package com.megatron.player

data class EpisodeSkipRange(val introEndMs: Long, val creditsStartMs: Long, val durationMs: Long)

fun episodeSkipRange(durationMs: Long, introSeconds: Int, outroSeconds: Int): EpisodeSkipRange? {
    if (durationMs <= 0) return null
    val intro = introSeconds.coerceIn(0, 3600) * 1000L
    val outro = outroSeconds.coerceIn(0, 3600) * 1000L
    if (intro + outro >= durationMs) return null
    return EpisodeSkipRange(intro, durationMs - outro, durationMs)
}

data class EpisodeSkipAction(val positionMs: Long, val finish: Boolean = false)

/** One state per playback: exact bookmarks and deliberate seeks always win over automation. */
class EpisodeSkipPolicy {
    private var episode = false
    private var exact = false
    private var introHandled = false
    private var tailHandled = false
    private var tailSuppressed = false

    fun configure(isEpisode: Boolean, exactStart: Boolean) {
        episode = isEpisode
        exact = exactStart
    }

    fun manualSeek(targetMs: Long, durationMs: Long, introSeconds: Int, outroSeconds: Int) {
        introHandled = true
        val range = episodeSkipRange(durationMs, introSeconds, outroSeconds) ?: return
        tailSuppressed = outroSeconds > 0 && targetMs >= range.creditsStartMs
    }

    fun next(positionMs: Long, durationMs: Long, seekable: Boolean, playing: Boolean,
        introSeconds: Int, outroSeconds: Int): EpisodeSkipAction? {
        if (!episode || exact || !seekable) return null
        val range = episodeSkipRange(durationMs, introSeconds, outroSeconds) ?: return null
        if (!introHandled) {
            introHandled = true
            if (positionMs < range.introEndMs) return EpisodeSkipAction(range.introEndMs)
        }
        if (!tailHandled && !tailSuppressed && playing && outroSeconds > 0 &&
            positionMs >= range.creditsStartMs && positionMs < durationMs) {
            tailHandled = true
            return EpisodeSkipAction(durationMs, finish = true)
        }
        return null
    }
}
