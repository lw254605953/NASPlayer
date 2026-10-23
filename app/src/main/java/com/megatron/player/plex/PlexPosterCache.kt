package com.megatron.player.plex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.megatron.player.source.CoalescingCache
import com.megatron.player.source.ReclaimableCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Shared jobs survive an offscreen cell; only UI subscribers are cancelled. */
internal object PlexPosterCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = ReclaimableCache<String, Bitmap>()
    // This merges jobs only; retained poster values have no application size/count cap.
    private val images = CoalescingCache<String, Bitmap>(0, 1)
    // Zero TTL: merge active downloads only; the disk, not File objects, is authoritative.
    private val files = CoalescingCache<String, File>(0, 1)
    private val downloads = Semaphore(4)
    private val decodes = Semaphore(2)
    private val diskLock = Any()
    private var lastPrunedAt = 0L
    private const val MAX_DISK = 256L * 1024 * 1024
    private const val MAX_FILE = 20L * 1024 * 1024

    fun peek(sourceKey: String, edge: Int): Bitmap? = memory.get("$sourceKey|$edge")

    suspend fun load(context: Context, sourceKey: String, edge: Int, fetch: () -> ByteArray): Bitmap {
        val app = context.applicationContext
        val key = "$sourceKey|$edge"
        memory.get(key)?.let { return it }
        return images.get(key, scope) {
            memory.get(key)?.let { return@get it }
            var file = sourceFile(app, sourceKey, fetch)
            decodes.withPermit {
                decode(file, edge) ?: run {
                    // Recover a truncated/invalid legacy cache entry with one fresh download.
                    synchronized(diskLock) { file.delete() }
                    file = sourceFile(app, sourceKey, fetch)
                    decode(file, edge) ?: throw IOException("Plex 图片无法解析")
                }
            }.also { memory.put(key, it) }
        }
    }

    private suspend fun sourceFile(context: Context, key: String, fetch: () -> ByteArray): File {
        val directory = File(context.cacheDir, "plex-posters").apply { mkdirs() }
        val file = File(directory, PlexDiskCache.digest(key))
        synchronized(diskLock) {
            if (file.isFile && file.length() in 1..MAX_FILE) {
                file.setLastModified(System.currentTimeMillis())
                return file
            }
        }
        return files.get(file.absolutePath, scope) {
            downloads.withPermit {
                synchronized(diskLock) {
                    if (file.isFile && file.length() in 1..MAX_FILE) return@withPermit file
                }
                val bytes = fetch()
                if (bytes.isEmpty() || bytes.size > MAX_FILE) throw IOException("Plex 图片大小无效")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Plex 图片无法解析")
                synchronized(diskLock) {
                    val temporary = File.createTempFile("poster_", ".tmp", directory)
                    try {
                        temporary.writeBytes(bytes)
                        check(temporary.renameTo(file)) { "封面缓存写入失败" }
                    } finally { temporary.delete() }
                    file.setLastModified(System.currentTimeMillis())
                    prune(directory, file)
                }
                file
            }
        }
    }

    private fun decode(file: File, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = PlexPosterSizing.sample(bounds.outWidth, bounds.outHeight, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= edge) return decoded
        val ratio = edge.toFloat() / largest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).roundToInt().coerceAtLeast(1),
            (decoded.height * ratio).roundToInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun prune(directory: File, keep: File) {
        val now = System.currentTimeMillis()
        if (now - lastPrunedAt in 0 until 15_000) return
        lastPrunedAt = now
        val entries = directory.listFiles().orEmpty().filter { it.isFile && it.extension != "tmp" }.sortedBy { it.lastModified() }
        var total = entries.sumOf { it.length() }; var count = entries.size
        for (old in entries) {
            if (total <= MAX_DISK && count <= 2048) break
            if (old != keep) { val size = old.length(); if (old.delete()) { total -= size; count-- } }
        }
    }
}
