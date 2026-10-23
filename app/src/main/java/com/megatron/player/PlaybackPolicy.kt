package com.megatron.player

import java.net.URI
import java.util.Locale

fun validatedHttpUrl(raw: String): String? {
    if (raw.any { it.code < 32 || it.code == 127 }) return null
    val text = raw.trim()
    val uri = try { URI(text) } catch (_: Exception) { return null }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    return URI(scheme + text.substring(text.indexOf(':'))).toASCIIString()
}

fun resumePositionMs(positionMs: Long, durationMs: Long): Long {
    if (positionMs <= 0L) return 0L
    if (durationMs > 0L && positionMs.toDouble() / durationMs >= 0.95) return 0L
    return positionMs
}

fun subtitleMimeType(fileName: String?): String? = when (fileName?.substringAfterLast('.')?.lowercase(Locale.ROOT)) {
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    "ass", "ssa" -> "text/x-ssa"
    else -> null
}
