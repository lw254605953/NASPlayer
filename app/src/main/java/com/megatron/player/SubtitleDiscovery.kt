package com.megatron.player

import android.net.Uri
import com.megatron.player.source.BrowserEntry
import com.megatron.player.source.SourceUris
import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest

data class NasSubtitle(val uri: Uri, val name: String)

/** Only sibling files are eligible. Language/forced suffixes require a real separator. */
internal fun sameNameSubtitles(videoPath: String, entries: List<BrowserEntry>): List<BrowserEntry> {
    val parent = videoPath.substringBeforeLast('/', "")
    val videoStem = normalizedSubtitleStem(videoPath.substringAfterLast('/').substringBeforeLast('.'))
    if (videoStem.isBlank()) return emptyList()
    return entries.filter { entry ->
        if (entry.isDirectory || entry.path.substringBeforeLast('/', "") != parent || subtitleMimeType(entry.path) == null) false
        else {
            val stem = normalizedSubtitleStem(entry.path.substringAfterLast('/').substringBeforeLast('.'))
            stem == videoStem || stem.startsWith(videoStem) && stem.getOrNull(videoStem.length) in listOf('.', '_', '-', '[')
        }
    }.sortedWith(compareBy<BrowserEntry> {
        normalizedSubtitleStem(it.path.substringAfterLast('/').substringBeforeLast('.')) != videoStem
    }.thenBy { it.name.lowercase(Locale.ROOT) })
}

private fun normalizedSubtitleStem(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun nasSubtitle(sourceId: String, entry: BrowserEntry): NasSubtitle =
    NasSubtitle(SourceUris.build(sourceId, entry.path), entry.path.substringAfterLast('/'))

/** Kept out of labels and URLs so an identically named embedded track cannot be mistaken for this file. */
internal fun externalSubtitleTrackId(uri: String): String = "mp-external-subtitle:" +
    MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun isExternalSubtitleFormat(id: String?, label: String?, expectedId: String, expectedLabel: String?): Boolean =
    id == expectedId && label == expectedLabel
