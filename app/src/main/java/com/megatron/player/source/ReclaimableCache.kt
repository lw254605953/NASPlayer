package com.megatron.player.source

import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference

/** No app-defined size/count cap; the runtime may reclaim values under memory pressure. */
internal class ReclaimableCache<K : Any, V : Any> {
    private class Entry<K, V>(val key: K, value: V, queue: ReferenceQueue<V>) : SoftReference<V>(value, queue)
    private val queue = ReferenceQueue<V>()
    private val values = HashMap<K, Entry<K, V>>()

    @Synchronized fun get(key: K): V? {
        drain()
        val entry = values[key] ?: return null
        return entry.get().also { if (it == null) values.remove(key) }
    }

    @Synchronized fun put(key: K, value: V) {
        drain()
        values[key] = Entry(key, value, queue)
    }

    private fun drain() {
        while (true) {
            val old = queue.poll() ?: return
            @Suppress("UNCHECKED_CAST")
            old as Entry<K, V>
            if (values[old.key] === old) values.remove(old.key)
        }
    }
}
