package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.DecoderPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-session decoder cache behind both Opus backends: the time/count
 * sweep gate, the TTL reap and the LRU trim to the cap. Everything is driven
 * by an injectable clock, so a 100-session convergence is deterministic.
 */
class DecoderPoolTest {

    private fun pool(
        max: Int = DecoderPool.DEFAULT_MAX_DECODERS,
        ttl: Long = DecoderPool.DEFAULT_TTL_MS,
        sweepEveryMs: Long = DecoderPool.DEFAULT_SWEEP_INTERVAL_MS,
        sweepAfterNewSessions: Int = DecoderPool.DEFAULT_SWEEP_AFTER_NEW_SESSIONS,
        clock: () -> Long,
        evicted: MutableList<Int> = mutableListOf(),
    ): DecoderPool<Int> = DecoderPool(
        maxDecoders = max,
        ttlMs = ttl,
        sweepIntervalMs = sweepEveryMs,
        sweepAfterNewSessions = sweepAfterNewSessions,
        clock = clock,
        onEvict = { evicted.add(it) },
    )

    @Test
    fun acquire_reusesTheSameDecoderForASession() {
        var now = 0L
        var created = 0
        val pool = pool(clock = { now })

        val first = pool.acquire(7) { created++; 7 }
        now = 1_000L
        val second = pool.acquire(7) { created++; 99 }

        assertEquals(7, first)
        assertEquals(7, second)
        assertEquals(1, created)
        assertEquals(1, pool.size)
    }

    @Test
    fun acquire_failedCreationLeavesNoEntry() {
        val pool = pool(clock = { 0L })

        assertNull(pool.acquire(1) { null })
        assertEquals(0, pool.size)
        assertFalse(pool.contains(1))
    }

    @Test
    fun sweep_reapsDecodersIdleBeyondTtl() {
        var now = 0L
        val evicted = mutableListOf<Int>()
        val pool = pool(
            ttl = 100L,
            sweepEveryMs = 100L,
            sweepAfterNewSessions = 1_000,
            clock = { now },
            evicted = evicted,
        )

        for (session in 1..40) pool.acquire(session) { session }
        assertEquals(40, pool.size)

        // Re-touching session 1 fires the time gate; every other session has
        // been idle past the TTL and must go.
        now = 101L
        pool.acquire(1) { error("must be reused, not recreated") }

        assertEquals(1, pool.size)
        assertTrue(pool.contains(1))
        assertEquals((2..40).toList(), evicted.sorted())
    }

    @Test
    fun sweep_newSessionCountGatesTheSweepAndTrimsByLru() {
        var now = 0L
        val evicted = mutableListOf<Int>()
        val pool = pool(
            max = 3,
            ttl = 1_000_000L,
            sweepEveryMs = 1_000_000L,
            sweepAfterNewSessions = 5,
            clock = { now },
            evicted = evicted,
        )

        for (session in 1..5) {
            now = session.toLong()
            pool.acquire(session) { session }
        }

        // The 5th new session triggered a sweep that evicted the LRU entry.
        assertFalse(pool.contains(1))
        assertTrue(pool.contains(5))
        assertEquals(4, pool.size)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun longLivedSessions_convergeToTheCap() {
        var now = 0L
        val pool = pool(clock = { now })

        for (session in 1..100) {
            now = session.toLong()
            pool.acquire(session) { session }
        }
        assertTrue("grew one-per-session: ${pool.size}", pool.size <= 48)

        // Force the time gate; all sessions are still within the TTL, so this
        // is a pure LRU trim down to the cap.
        now = 100L + DecoderPool.DEFAULT_SWEEP_INTERVAL_MS
        pool.acquire(100) { error("must be reused, not recreated") }

        assertEquals(DecoderPool.DEFAULT_MAX_DECODERS, pool.size)
        assertTrue(pool.contains(100))
        assertTrue("most recently used must survive", pool.contains(69))
        assertFalse("least recently used must be evicted", pool.contains(68))
    }

    @Test
    fun clear_evictsEveryDecoder() {
        val evicted = mutableListOf<Int>()
        val pool = pool(clock = { 0L }, evicted = evicted)

        for (session in 1..5) pool.acquire(session) { session }
        pool.clear()

        assertEquals(0, pool.size)
        assertEquals((1..5).toList(), evicted.sorted())
    }
}
