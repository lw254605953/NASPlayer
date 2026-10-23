package com.megatron.player.source

import kotlinx.coroutines.*

/** Bounded short-lived results plus independent in-flight work; screen disposal does not refetch it. */
internal class CoalescingCache<K, V>(private val ttlMs: Long, private val capacity: Int,
    private val maxWeight: Int = Int.MAX_VALUE, private val weight: (V) -> Int = { 1 },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry<V>(val value: V, val at: Long, val weight: Int)
    private val lock = Any()
    private val values = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private val jobs = mutableMapOf<K, Deferred<V>>()
    suspend fun get(key: K, scope: CoroutineScope, force: Boolean = false, load: suspend () -> V): V {
        val task = synchronized(lock) {
            if (!force) values[key]?.takeIf { clock() - it.at in 0 until ttlMs }?.let { return it.value }
            values.remove(key)
            jobs[key]?.let { return@synchronized it }
            val work = scope.async(start = CoroutineStart.LAZY) {
                val result = load()
                val activeJob = currentCoroutineContext().job
                synchronized(lock) {
                    activeJob.ensureActive()
                    val size = weight(result).coerceAtLeast(1)
                    if (size <= maxWeight) values[key] = Entry(result, clock(), size)
                    while (values.size > capacity || values.values.sumOf { it.weight.toLong() } > maxWeight) values.remove(values.keys.first())
                }
                result
            }
            jobs[key] = work
            work.invokeOnCompletion { synchronized(lock) { if (jobs[key] === work) jobs.remove(key) } }
            work.start(); work
        }
        return task.await()
    }
    fun invalidate(key: K) = synchronized(lock) { values.remove(key); jobs.remove(key)?.cancel(); Unit }
}
