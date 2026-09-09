package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.UdpAvailability
import dev.woms.mumdroid.service.UdpFallbackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpFallbackControllerTest {

    private var now = 0L

    private fun controller() = UdpFallbackController(nowMs = { now })

    /** A controller reset to UDP-available, probe armed, grace window elapsed. */
    private fun pastGrace(): UdpFallbackController {
        now = 100_000
        val c = UdpFallbackController(nowMs = { now })
        c.resetTo(forceTcp = false)
        c.markProbeStarted()
        now += UdpAvailability.GRACE_MS + 1
        return c
    }

    @Test
    fun resetTo_matchesForceTcpState() {
        val c = controller()
        c.resetTo(forceTcp = false)
        assertTrue(c.udpAvailable)
        assertFalse(c.useTcp(false))
        assertTrue(c.useTcp(true))
        c.resetTo(forceTcp = true)
        assertFalse(c.udpAvailable)
        assertTrue(c.useTcp(false))
        assertEquals(0L, c.probeStartMs)
    }

    @Test
    fun fallbackWaitsForProbeStartAndGraceWindow() {
        val c = controller()
        c.resetTo(forceTcp = false)
        now = 1_000_000
        // Probe never started: no fallback regardless of counters.
        assertNull(c.evaluate(false, remoteGood = 0, localGood = 0))
        c.markProbeStarted()
        assertEquals(1_000_000L, c.probeStartMs)
        // Inside the official 20 s grace window: still no fallback.
        assertNull(c.evaluate(false, remoteGood = 0, localGood = 0))
        now += UdpAvailability.GRACE_MS + 1
        val decision = c.evaluate(false, remoteGood = 0, localGood = 0)
        assertTrue(decision is UdpFallbackController.Decision.Fallback)
        assertEquals(
            UdpFallbackController.Reason.BOTH_DOWN,
            (decision as UdpFallbackController.Decision.Fallback).reason,
        )
        assertFalse(c.udpAvailable)
    }

    @Test
    fun fallbackReasonMapsToBrokenSide() {
        assertEquals(
            UdpFallbackController.Reason.SEND_BROKEN,
            (pastGrace().evaluate(false, remoteGood = 0, localGood = 5)
                as UdpFallbackController.Decision.Fallback).reason,
        )
        assertEquals(
            UdpFallbackController.Reason.RECEIVE_BROKEN,
            (pastGrace().evaluate(false, remoteGood = 5, localGood = 0)
                as UdpFallbackController.Decision.Fallback).reason,
        )
    }

    @Test
    fun healthyCountersProposeNothing() {
        assertNull(pastGrace().evaluate(false, remoteGood = 5, localGood = 5))
    }

    @Test
    fun restoreRequiresBothSidesAboveThreshold() {
        val c = pastGrace()
        c.evaluate(false, remoteGood = 0, localGood = 0)
        now += 1_000
        // RESTORE_GOOD = 3: both sides must exceed it.
        assertNull(c.evaluate(false, remoteGood = 3, localGood = 5))
        assertTrue(c.evaluate(false, remoteGood = 4, localGood = 4)
            is UdpFallbackController.Decision.Restore)
        assertTrue(c.udpAvailable)
        assertFalse(c.useTcp(false))
    }

    @Test
    fun markUnavailableGuardsDoubleFallbackAndForceTcp() {
        val c = controller()
        c.resetTo(forceTcp = false)
        assertFalse(c.markUnavailable(forceTcp = true))
        assertTrue(c.markUnavailable(forceTcp = false))
        assertFalse(c.markUnavailable(forceTcp = false))
    }

    @Test
    fun isFallbackActiveRequiresLiveUdplessSession() {
        val c = controller()
        c.resetTo(forceTcp = false)
        assertFalse(c.isFallbackActive(false, live = true))
        c.markUnavailable(false)
        assertTrue(c.isFallbackActive(false, live = true))
        assertFalse(c.isFallbackActive(false, live = false))
        assertFalse(c.isFallbackActive(true, live = true))
    }
}
