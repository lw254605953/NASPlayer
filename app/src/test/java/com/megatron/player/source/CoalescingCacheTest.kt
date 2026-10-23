package com.megatron.player.source

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CoalescingCacheTest {
    @Test fun repeatedViewsReuseResultsAndManualRefreshLoadsOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = CoalescingCache<String,Int>(10000,5);val calls=AtomicInteger()
            assertEquals(1,cache.get("dir",scope){ calls.incrementAndGet() })
            assertEquals(1,cache.get("dir",scope){ calls.incrementAndGet() })
            assertEquals(2,cache.get("dir",scope,true){ calls.incrementAndGet() })
            assertEquals(2,calls.get())
        } finally { scope.cancel() }
    }
    @Test fun concurrentConsumersShareARequestAndClosingOneDoesNotCancelOthers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache=CoalescingCache<String,Int>(10000,5);val calls=AtomicInteger();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val first=async { cache.get("dir",scope){ calls.incrementAndGet();started.complete(Unit);release.await();42 } }
            started.await();first.cancelAndJoin()
            val others=(1..8).map { async { cache.get("dir",scope){ calls.incrementAndGet();42 } } }
            release.complete(Unit)
            assertTrue(others.awaitAll().all { it==42 });assertEquals(1,calls.get())
        } finally { scope.cancel() }
    }
    @Test fun failuresRetryAndIdentityAndExpiryAreIsolated() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            var now=0L;val cache=CoalescingCache<String,Int>(100,3,clock={now});var calls=0
            try { cache.get("userA",scope){ error("offline") };fail() } catch (_:IllegalStateException) { }
            assertEquals(1,cache.get("userA",scope){++calls});assertEquals(2,cache.get("userB",scope){++calls})
            now=101;assertEquals(3,cache.get("userA",scope){++calls})
        } finally { scope.cancel() }
    }
    @Test fun refreshCancelsAnOldRequestWithoutPublishingItOverTheNewResult() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=CoalescingCache<String,Int>(10000,3);val started=CompletableDeferred<Unit>()
            val first=async { cache.get("dir",scope){started.complete(Unit);delay(10000);1} }
            started.await();cache.invalidate("dir")
            assertEquals(2,cache.get("dir",scope){2})
            try { first.await();fail() } catch (_:CancellationException) { }
            assertEquals(2,cache.get("dir",scope){3})
        } finally { scope.cancel() }
    }
    @Test fun evictionAndInvalidationDoNotRetainOldEntries() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=CoalescingCache<String,List<Int>>(10000,3,4,{it.size});var calls=0
            cache.get("a",scope){calls++;listOf(1,2,3)};cache.get("b",scope){calls++;listOf(4,5,6)}
            cache.get("a",scope){calls++;listOf(1)};assertEquals(3,calls)
            cache.invalidate("a");cache.get("a",scope){calls++;listOf(2)};assertEquals(4,calls)
        } finally { scope.cancel() }
    }
}
