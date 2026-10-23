package com.megatron.player.cast

import android.net.Uri
import com.megatron.player.plex.PlexUris

fun castMimeType(uri: Uri, title: String, container: String? = null): String {
    if (container?.startsWith("video/") == true) return container
    val path = PlexUris.parse(uri)?.partKey ?: uri.lastPathSegment.orEmpty()
    return when ((path.takeIf { '.' in it } ?: title).substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "f4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }
}
