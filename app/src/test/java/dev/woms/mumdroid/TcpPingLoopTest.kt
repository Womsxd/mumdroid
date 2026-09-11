package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.TcpPingLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TCP keep-alive ping budget and RTT rules, which previously could only be
 * observed by waiting five seconds per tick against a real socket.
 */
class TcpPingLoopTest {

    private class Harness(clockStart: Long = 1_000L) {
        var now = clockStart
        val sent = mutableListOf<Long>()
        val rtts = mutableListOf<Long>()
        var timeouts = 0
        val loop = TcpPingLoop(
            tag = "test",
            intervalSeconds = 1L,
            maxInFlight = 4,
            clock = { now },
            sendPing = { sent += it },
            onTimeout = { timeouts++ },
            onRtt = { rtts += it },
        )

        fun tick() = loop.tick()
    }

    @Test
    fun tick_sendsAPingStampedWithTheMonotonicClock() {
        val h = Harness()
        h.tick()
        assertEquals(listOf(1_000L), h.sent)
        assertEquals(1, h.loop.inFlightCount)
    }

    @Test
    fun tick_timesOutWhenTheInFlightBudgetIsSpent() {
        val h = Harness()
        repeat(4) { h.tick() }
        // Four unanswered pings are tolerated; the fifth tick gives up instead
        // of sending another one.
        assertEquals(4, h.sent.size)
        assertEquals(0, h.timeouts)
        h.tick()
        assertEquals(1, h.timeouts)
        // A timed-out loop must not keep spraying pings.
        assertEquals(4, h.sent.size)
    }

    @Test
    fun onReply_clearsTheBudgetAndReportsTheRtt() {
        val h = Harness()
        repeat(4) { h.tick() }
        h.now = 1_250L
        val rtt = h.loop.onReply(timestampMs = 1_000L)
        assertEquals(250L, rtt)
        assertEquals(listOf(250L), h.rtts)
        assertEquals(0, h.loop.inFlightCount)
        // The budget is usable again.
        h.tick()
        assertEquals(5, h.sent.size)
    }

    @Test
    fun onReply_discardsTimestampsThatAreNotInThePast() {
        val h = Harness()
        assertNull(h.loop.onReply(timestampMs = 0L))
        assertNull(h.loop.onReply(timestampMs = -5L))
        // A timestamp from the future cannot yield a meaningful RTT.
        assertNull(h.loop.onReply(timestampMs = h.now + 1))
        assertNull(h.loop.onReply(timestampMs = h.now))
        assertTrue(h.rtts.isEmpty())
    }

    @Test
    fun onReply_discardsStaleRepliesThatWouldPolluteTheAverage() {
        val h = Harness()
        h.now = 100_000L
        // Exactly 60 s is already stale: the sample would be an artefact of a
        // long stall, not a real round trip.
        assertNull(h.loop.onReply(timestampMs = 100_000L - 60_000L))
        assertEquals(59_999L, h.loop.onReply(timestampMs = 100_000L - 59_999L))
    }

    @Test
    fun onReply_clearsTheBudgetEvenWhenTheTimestampIsUnusable() {
        val h = Harness()
        repeat(3) { h.tick() }
        assertNull(h.loop.onReply(timestampMs = 0L))
        // The server did answer, so the in-flight budget must be reset.
        assertEquals(0, h.loop.inFlightCount)
    }

    @Test
    fun stop_isIdempotentAndResetsTheBudget() {
        val h = Harness()
        repeat(2) { h.tick() }
        h.loop.stop()
        assertEquals(0, h.loop.inFlightCount)
        h.loop.stop()
    }
}
