package com.megatron.player.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** Persistent album thumbnails, shared by scan and playback and deduplicated by image content. */
internal class MusicArtworkStore(context: Context) {
    private val directory = File(context.filesDir, "music_art").apply { mkdirs() }

    fun save(bytes: ByteArray): String? = synchronized(lock) {
        if (bytes.isEmpty() || bytes.size > MusicTagParser.MAX_ART) return@synchronized null
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$hash.jpg")
        if (file.isFile) { file.setLastModified(System.currentTimeMillis()); return@synchronized file.absolutePath }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@synchronized null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 768 || bounds.outHeight / inSampleSize > 768) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@synchronized null
        val temporary = File.createTempFile("art_", ".tmp", directory)
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            check(temporary.renameTo(file))
        } finally { bitmap.recycle(); temporary.delete() }
        val now = System.currentTimeMillis()
        if (now - lastPrunedAt > 15_000) {
            lastPrunedAt = now
            val files = directory.listFiles().orEmpty().filter { it.extension == "jpg" }
            var total = files.sumOf { it.length() }
            if (total > MAX_BYTES) files.sortedBy { it.lastModified() }.forEach {
                if (total > MAX_BYTES && it != file) { val size = it.length(); if (it.delete()) total -= size }
            }
        }
        file.absolutePath
    }

    companion object {
        private val lock = Any()
        private var lastPrunedAt = 0L
        private const val MAX_BYTES = 256L * 1024 * 1024
    }
}
