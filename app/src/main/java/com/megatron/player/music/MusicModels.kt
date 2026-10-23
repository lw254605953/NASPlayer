package com.megatron.player.music

import android.net.Uri
import com.megatron.player.source.SourceUris
import org.json.JSONObject
import java.security.MessageDigest

data class MusicRoot(val sourceId: String, val path: String, val name: String)

data class MusicTrack(
    val id: String, val sourceId: String, val path: String, val title: String,
    val artist: String = "", val album: String = "", val durationMs: Long = 0,
    val format: String = "", val sampleRate: Int = 0, val bitDepth: Int = 0,
    val trackNumber: Int = 0, val discNumber: Int = 0, val artworkPath: String? = null,
    val sizeBytes: Long = 0, val modifiedAt: Long = 0, val indexedAt: Long = 0,
    val metadataVersion: Int = 0,
) {
    val uri: Uri get() = SourceUris.build(sourceId, path)
}

enum class MusicMode { SEQUENCE, LOOP, ONE, SHUFFLE }

data class MusicPlaybackState(
    val current: MusicTrack? = null, val queue: List<MusicTrack> = emptyList(),
    val positionMs: Long = 0, val durationMs: Long = 0, val isPlaying: Boolean = false,
    val mode: MusicMode = MusicMode.SEQUENCE, val error: String? = null, val connected: Boolean = false,
    val outputInfo: String = "",
    val dismissed: Boolean = false,
)

fun isMusicFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in
    setOf("mp3", "flac", "wav", "ape", "m4a", "m4r", "aac", "ogg", "opus")

internal fun musicContainerMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "m4a", "m4r" -> "audio/mp4"
    else -> null
}

internal fun musicId(sourceId: String, path: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$sourceId\u0000$path".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun MusicTrack.json(): JSONObject = JSONObject().apply {
    put("id", id); put("source", sourceId); put("path", path); put("title", title)
    put("artist", artist); put("album", album); put("duration", durationMs); put("format", format)
    put("sampleRate", sampleRate); put("bitDepth", bitDepth); put("track", trackNumber); put("disc", discNumber)
    put("art", artworkPath ?: JSONObject.NULL); put("size", sizeBytes); put("modified", modifiedAt); put("indexed", indexedAt); put("metadataVersion", metadataVersion)
}
internal fun musicTrack(j: JSONObject): MusicTrack = MusicTrack(
    j.getString("id"), j.getString("source"), j.getString("path"), j.getString("title"),
    j.optString("artist"), j.optString("album"), j.optLong("duration"), j.optString("format"),
    j.optInt("sampleRate"), j.optInt("bitDepth"), j.optInt("track"), j.optInt("disc"),
    if (j.isNull("art")) null else j.optString("art").takeIf { it.isNotBlank() },
    j.optLong("size"), j.optLong("modified"), j.optLong("indexed"), j.optInt("metadataVersion"),
)

internal const val MUSIC_METADATA_VERSION = 1

internal fun musicNeedsMetadata(track: MusicTrack): Boolean = track.indexedAt <= 0 ||
    track.metadataVersion < MUSIC_METADATA_VERSION || track.artworkPath?.let { !java.io.File(it).isFile } == true
