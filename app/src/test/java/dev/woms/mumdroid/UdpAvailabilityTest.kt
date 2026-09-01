package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.UdpAvailability
import dev.woms.mumdroid.core.net.UdpVoiceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UdpAvailabilityTest {

    @Test
    fun doesNotFallbackBeforeUdpStarts() {
        assertFalse(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = false,
                udpProbeStartMs = 0L,
                nowMs = 60_000L,
                remoteGood = 0,
                localGood = 0,
            ),
        )
    }

    @Test
    fun doesNotFallbackDuringGrace() {
        assertFalse(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = false,
                udpProbeStartMs = 1_000L,
                nowMs = 1_000L + UdpAvailability.GRACE_MS,
                remoteGood = 0,
                localGood = 0,
            ),
        )
    }

    @Test
    fun fallsBackAfterGraceWhenEitherSideHasNoGoodPackets() {
        assertTrue(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = false,
                udpProbeStartMs = 1_000L,
                nowMs = 1_001L + UdpAvailability.GRACE_MS,
                remoteGood = 0,
                localGood = 12,
            ),
        )
        assertTrue(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = false,
                udpProbeStartMs = 1_000L,
                nowMs = 1_001L + UdpAvailability.GRACE_MS,
                remoteGood = 8,
                localGood = 0,
            ),
        )
    }

    @Test
    fun staysOnUdpWhenBothSidesHaveTraffic() {
        assertFalse(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = false,
                udpProbeStartMs = 1_000L,
                nowMs = 30_000L,
                remoteGood = 4,
                localGood = 4,
            ),
        )
    }

    @Test
    fun forceTcpNeverEvaluatesFallback() {
        assertFalse(
            UdpAvailability.shouldFallbackToTcp(
                udpAvailable = true,
                forceTcp = true,
                udpProbeStartMs = 1L,
                nowMs = 60_000L,
                remoteGood = 0,
                localGood = 0,
            ),
        )
    }

    @Test
    fun restoresUdpAfterBothSidesRecover() {
        assertTrue(
            UdpAvailability.shouldRestoreUdp(
                udpAvailable = false,
                forceTcp = false,
                remoteGood = 4,
                localGood = 4,
            ),
        )
        assertFalse(
            UdpAvailability.shouldRestoreUdp(
                udpAvailable = false,
                forceTcp = false,
                remoteGood = 3,
                localGood = 4,
            ),
        )
        assertFalse(
            UdpAvailability.shouldRestoreUdp(
                udpAvailable = false,
                forceTcp = true,
                remoteGood = 10,
                localGood = 10,
            ),
        )
    }

    @Test
    fun peerMatchesTreatsIpv4MappedAsIpv4() {
        val v4 = InetAddress.getByName("192.0.2.10")
        val mapped = InetAddress.getByName("::ffff:192.0.2.10")
        assertTrue(UdpVoiceManager.peerMatches(mapped, 64738, v4, 64738))
        assertFalse(UdpVoiceManager.peerMatches(mapped, 64738, v4, 64739))
        assertFalse(UdpVoiceManager.peerMatches(InetAddress.getByName("192.0.2.11"), 64738, v4, 64738))
    }
}
