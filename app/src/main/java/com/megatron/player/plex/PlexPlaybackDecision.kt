package com.megatron.player.plex

import java.io.IOException
import org.json.JSONObject

data class PlexMediaSelection(val mediaIndex: Int, val partIndex: Int, val durationMs: Long)

data class PlexPlaybackDecision(
    val generalCode: Int,
    val directPlayCode: Int,
    val mdeCode: Int,
    val terminationCode: Int,
    val generalText: String,
    val directPlayText: String,
    val mdeText: String,
    val partDecision: String,
    val durationMs: Long,
) {
    val allowsDirectPlay: Boolean get() {
        if (terminationCode >= 0 || generalCode >= 2000 || partDecision in setOf("transcode", "copy")) return false
        // PMS's MDE result accounts for hasMDE=1; a generic profile's direct-play result may differ.
        val finalCode = when { mdeCode >= 0 -> mdeCode; directPlayCode >= 0 -> directPlayCode; else -> generalCode }
        return finalCode == 1000
    }

    val serverText: String get() = listOf(mdeText, directPlayText, generalText).filter { it.isNotBlank() }.distinct().joinToString(" / ")
    fun diagnosticText(): String = "general=$generalCode · directPlay=$directPlayCode · mde=$mdeCode · termination=$terminationCode · part=${partDecision.ifEmpty { "未提供" }}\n${serverText.ifBlank { "服务器未提供说明" }}"

    fun requireDirectPlay() {
        if (allowsDirectPlay) return
        val prefix = when {
            terminationCode >= 0 || listOf(generalCode, mdeCode).any { it >= 2000 } -> "Plex 拒绝此次播放"
            mdeCode == 1001 || generalCode == 1001 || partDecision in setOf("transcode", "copy") -> "Plex 仅允许转换后播放，当前版本支持原文件直放"
            directPlayCode >= 2000 -> "Plex 未允许原文件直放"
            else -> "Plex 未返回可确认的直放许可"
        }
        throw PlexPlaybackFailure(prefix, serverText, decision = this)
    }

    companion object {
        fun fromJson(json: JSONObject, ratingKey: String, selection: PlexMediaSelection): PlexPlaybackDecision {
            val c = json.optJSONObject("MediaContainer") ?: json
            val items = c.optJSONArray("Metadata")
            val item = items?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("ratingKey") == ratingKey } }
            if (items != null && items.length() > 0 && item == null) throw PlexPlaybackFailure("Plex 返回的播放影片不匹配")
            val media = item?.optJSONArray("Media")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optBoolean("selected") } ?: a.optJSONObject(selection.mediaIndex) ?: a.optJSONObject(0)
            }
            val part = media?.optJSONArray("Part")?.let { a -> a.optJSONObject(selection.partIndex) ?: a.optJSONObject(0) }
            return PlexPlaybackDecision(
                c.optInt("generalDecisionCode", -1), c.optInt("directPlayDecisionCode", -1),
                c.optInt("mdeDecisionCode", -1), c.optInt("terminationCode", -1),
                safePlexDecisionText(c.optString("generalDecisionText")),
                safePlexDecisionText(c.optString("directPlayDecisionText")),
                safePlexDecisionText(c.optString("terminationText").ifBlank { c.optString("mdeDecisionText") }),
                part?.optString("decision").orEmpty().lowercase(java.util.Locale.ROOT).takeIf { it in setOf("directplay", "copy", "transcode") }.orEmpty(),
                item?.optLong("duration", selection.durationMs) ?: selection.durationMs,
            )
        }
    }
}

class PlexPlaybackFailure(
    val userMessage: String,
    val serverText: String = "",
    val httpStatus: Int? = null,
    val decision: PlexPlaybackDecision? = null,
    cause: Throwable? = null,
) : IOException(userMessage, cause) {
    fun description(): String = buildString {
        append(userMessage)
        httpStatus?.let { append("（HTTP $it）") }
        if (serverText.isNotEmpty()) append("\n$serverText")
    }
}

fun Throwable.plexPlaybackFailure(): PlexPlaybackFailure? {
    val seen = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.size < 12) {
        val value = current
        if (value is PlexPlaybackFailure) return value
        if (seen.any { it === value }) break
        seen += value
        current = value.cause
    }
    return null
}

/** Server strings are display data. Keep addresses, credentials and local paths out of copied diagnostics. */
internal fun safePlexDecisionText(raw: String): String = raw.take(4096)
    .replace(Regex("(?i)https?://[^\\s<>\"']+"), "[地址已隐藏]")
    .replace(Regex("(?i)(?:authorization|x-plex-token|access_?token|authToken|password|token)\\s*[\"']?\\s*(?:=>|[:=])\\s*[\"']?(?:Bearer\\s+)?[^\\s&\"'<>]+"), "[凭据已隐藏]")
    .replace(Regex("(?i)Bearer\\s+[a-z0-9._~+/=-]+"), "[凭据已隐藏]")
    .replace(Regex("(?<![a-z0-9])(?:/[a-z0-9_@.-]+){2,}[^\\s\"'<>]*", RegexOption.IGNORE_CASE), "[路径已隐藏]")
    .replace(Regex("[\\r\\n\\t]+"), " ").trim().take(512)
