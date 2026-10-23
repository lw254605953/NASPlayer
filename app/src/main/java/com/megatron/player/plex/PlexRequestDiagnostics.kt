package com.megatron.player.plex

import com.megatron.player.playbackFailureReason

/** A short in-memory trace: operation, duration, HTTP status and error type only. */
internal object PlexRequestDiagnostics {
    private val recent = ArrayDeque<String>()

    fun record(operation: String, startedNs: Long, status: Int?, error: Exception?) {
        val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
        val result = buildString {
            if (status != null) append("HTTP $status")
            if (error != null) {
                if (isNotEmpty()) append(" · ")
                append(error.javaClass.simpleName)
                playbackFailureReason(error).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
            }
            if (isEmpty()) append("完成")
        }
        synchronized(recent) {
            if (recent.size == 8) recent.removeFirst()
            recent.addLast("$operation：${elapsedMs}ms · $result")
        }
    }

    fun snapshot(): String = synchronized(recent) { recent.joinToString("\n") }
}
