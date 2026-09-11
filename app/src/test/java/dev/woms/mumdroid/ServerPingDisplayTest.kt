package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.ui.screen.LatencyGrade
import dev.woms.mumdroid.ui.screen.PingHealth
import dev.woms.mumdroid.ui.screen.ServerPingDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server card's latency buckets and bar count. Kept out of the composable
 * so the thresholds can be checked without a Compose runtime.
 */
class ServerPingDisplayTest {

    private fun ping(
        pingMs: Int? = null,
        reachable: Boolean = true,
        updatedAtMs: Long = 1L,
    ) = ServerPingInfo(pingMs = pingMs, reachable = reachable, updatedAtMs = updatedAtMs)

    @Test
    fun litBars_isEmptyWhileProbing() {
        assertEquals(0, ServerPingDisplay.litBars(null))
        // reachable=false with updatedAtMs=0 is the "still pinging" state.
        assertEquals(0, ServerPingDisplay.litBars(ServerPingInfo()))
    }

    @Test
    fun litBars_isEmptyWhenUnreachable() {
        assertEquals(0, ServerPingDisplay.litBars(ping(pingMs = 12, reachable = false)))
    }

    @Test
    fun litBars_isOneWhenReachableWithoutLatency() {
        assertEquals(1, ServerPingDisplay.litBars(ping(pingMs = null)))
    }

    @Test
    fun litBars_lightUpWithTheGrade() {
        assertEquals(3, ServerPingDisplay.litBars(ping(pingMs = 49)))
        assertEquals(2, ServerPingDisplay.litBars(ping(pingMs = 50)))
        assertEquals(2, ServerPingDisplay.litBars(ping(pingMs = 149)))
        assertEquals(1, ServerPingDisplay.litBars(ping(pingMs = 150)))
    }

    @Test
    fun grade_boundariesAreExclusiveOnTheFastSide() {
        assertEquals(LatencyGrade.FAST, ServerPingDisplay.grade(0))
        assertEquals(LatencyGrade.FAST, ServerPingDisplay.grade(49))
        assertEquals(LatencyGrade.MEDIUM, ServerPingDisplay.grade(50))
        assertEquals(LatencyGrade.MEDIUM, ServerPingDisplay.grade(149))
        assertEquals(LatencyGrade.SLOW, ServerPingDisplay.grade(150))
        assertEquals(LatencyGrade.SLOW, ServerPingDisplay.grade(9_999))
    }

    @Test
    fun health_distinguishesTheFourCardStates() {
        assertEquals(PingHealth.PROBING, ServerPingDisplay.health(null))
        assertEquals(PingHealth.PROBING, ServerPingDisplay.health(ServerPingInfo()))
        assertEquals(PingHealth.UNREACHABLE, ServerPingDisplay.health(ping(reachable = false)))
        assertEquals(PingHealth.NO_LATENCY, ServerPingDisplay.health(ping(pingMs = null)))
        assertEquals(PingHealth.MEASURED, ServerPingDisplay.health(ping(pingMs = 30)))
    }
}
