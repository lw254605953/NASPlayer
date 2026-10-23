package com.megatron.player.source

import java.lang.ref.SoftReference
import org.junit.Assert.*
import org.junit.Test

class ReclaimableCacheTest {
    @Test fun hasNoFixedEntryLimitAndRetainsAvailableValues() {
        val cache = ReclaimableCache<Int, Any>()
        val retained = List(2000) { Any() }
        retained.forEachIndexed { i, value -> cache.put(i, value) }
        retained.forEachIndexed { i, value -> assertSame(value, cache.get(i)) }
    }

    @Test fun runtimeReclamationRemovesOnlyTheReclaimedVersion() {
        val cache = ReclaimableCache<String, Any>()
        val first = Any(); val replacement = Any()
        cache.put("poster", first)
        val field = cache.javaClass.getDeclaredField("values").apply { isAccessible = true }
        val values = field.get(cache) as Map<*, *>
        val old = values["poster"] as SoftReference<*>
        cache.put("poster", replacement)
        old.clear(); old.enqueue()
        assertSame(replacement, cache.get("poster"))
        val current = values["poster"] as SoftReference<*>
        current.clear(); current.enqueue()
        assertNull(cache.get("poster"))
    }
}
