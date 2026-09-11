package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.UdpPingTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpPingTrackerTest {

    @Test
    fun record_accumulatesCountMeanAndVariance() {
        val tracker = UdpPingTracker()
        assertTrue(tracker.record(10))
        assertTrue(tracker.record(20))
        assertTrue(tracker.record(30))
        assertEquals(3, tracker.count)
        assertEquals(20L, tracker.meanMillis)
        // Population variance: (10² + 20² + 30²) / 3 − 20².
        assertEquals(66.67f, tracker.varianceMillisSquared, 0.01f)
    }

    @Test
    fun record_rejectsOutOfRangeSamples() {
        val tracker = UdpPingTracker()
        assertFalse(tracker.record(-1))
        assertFalse(tracker.record(UdpPingTracker.MAX_RTT_MS + 1))
        assertEquals(0, tracker.count)
        assertEquals(0L, tracker.meanMillis)
        assertEquals(0f, tracker.varianceMillisSquared, 0f)
        // The bounds themselves are accepted.
        assertTrue(tracker.record(0))
        assertTrue(tracker.record(UdpPingTracker.MAX_RTT_MS))
        assertEquals(2, tracker.count)
    }

    @Test
    fun reset_discardsStatistics() {
        val tracker = UdpPingTracker()
        tracker.record(42)
        tracker.reset()
        assertEquals(0, tracker.count)
        assertEquals(0L, tracker.meanMillis)
        assertEquals(0f, tracker.varianceMillisSquared, 0f)
    }


    @Test
    fun cadence_primesTheClockOnTheFirstTick() {
        val cadence = UdpPingTracker.Cadence(5_000L)
        // The first tick only starts the clock: a ping right after bind would
        // race a fast reply into the gap before the next receive().
        assertFalse(cadence.due(1_000L))
        assertFalse(cadence.due(1_001L))
        assertFalse(cadence.due(5_999L))
        // A full interval after the priming tick is due.
        assertTrue(cadence.due(6_000L))
    }

    @Test
    fun cadence_sendsAtMostOnePerInterval() {
        val cadence = UdpPingTracker.Cadence(5_000L)
        cadence.due(0L)
        assertTrue(cadence.due(5_000L))
        assertFalse(cadence.due(5_001L))
        assertFalse(cadence.due(9_999L))
        assertTrue(cadence.due(10_000L))
    }

    @Test
    fun cadence_resetMakesTheNextTickPrimeAgain() {
        val cadence = UdpPingTracker.Cadence(5_000L)
        cadence.due(0L)
        assertTrue(cadence.due(5_000L))
        cadence.reset()
        // Stopping and starting the datagram socket must not fire immediately.
        assertFalse(cadence.due(5_100L))
        assertTrue(cadence.due(10_100L))
    }

    @Test
    fun cadence_isUnchangedByATickThatIsNotDue() {
        val cadence = UdpPingTracker.Cadence(5_000L)
        cadence.due(1_000L)
        // A not-due tick must not push the next send further out.
        assertFalse(cadence.due(3_000L))
        assertTrue(cadence.due(6_000L))
    }
}
