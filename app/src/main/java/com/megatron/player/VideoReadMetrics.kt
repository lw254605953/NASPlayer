package com.megatron.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.ArrayDeque

/** Bounded buckets of actual application reads; this is not an estimate of network link capacity. */
class ReadWindow(private val now: () -> Long) {
    private data class Bucket(val at: Long, var value: Long)
    private val buckets = ArrayDeque<Bucket>()
    private val startedAt = now()
    @Synchronized fun add(value: Long) {
        if (value <= 0) return
        val time = now(); trim(time)
        val bucketTime = time / 500 * 500
        if (buckets.peekLast()?.at == bucketTime) buckets.peekLast().value += value else buckets.add(Bucket(bucketTime, value))
    }
    private fun trim(time: Long) { while (buckets.isNotEmpty() && time - buckets.first.at >= 5000) buckets.removeFirst() }
    @Synchronized fun count(): Long { trim(now()); return buckets.sumOf { it.value } }
    fun perSecond(): Double = count() * 1000.0 / (now() - startedAt).coerceIn(1000, 5000)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VideoReadMetrics(private val expectedUri: Uri) {
    val reads = ReadWindow(SystemClock::elapsedRealtime)
    @Volatile var fileLength = 0L
        private set
    @Volatile var hasReads = false
        private set
    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val source = factory.createDataSource()
        object : DataSource by source {
            private var measure = false
            override fun open(spec: DataSpec): Long {
                measure = spec.uri == expectedUri
                val length = source.open(spec)
                if (measure && expectedUri.scheme in setOf("mpplex", "mpdav", "file", "content") && spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong() && length > 0) fileLength = length
                return length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length).also {
                if (measure && it > 0) { reads.add(it.toLong()); hasReads = true }
            }
        }
    }
}
