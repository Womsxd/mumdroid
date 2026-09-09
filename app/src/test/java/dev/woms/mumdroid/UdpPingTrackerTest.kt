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
}
