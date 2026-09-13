package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.service.UdpFallbackController
import dev.woms.mumdroid.service.VoiceBandwidthController
import dev.woms.mumdroid.service.VoiceUdpSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UDP voice transport's glue: the TCP/fallback decision it exposes, the
 * bandwidth reconfigure hand-off and the empty stats before a socket exists.
 * Any real datagram path needs a live [dev.woms.mumdroid.core.net.UdpVoiceManager],
 * so it is left out here.
 */
class VoiceUdpSessionTest {

    private class FakeHost : VoiceUdpSession.Host {
        var appSettings = AppSettings()
        var forceTcpValue = false
        var framesPerPacketChanges = 0
        val statuses = mutableListOf<String>()
        val systemMessages = mutableListOf<String>()

        override fun settings(): AppSettings = appSettings
        override fun forceTcp(): Boolean = forceTcpValue
        override fun client(): MumbleClient? = null
        override fun onAudioPacket(
            session: Int,
            frameNumber: Long,
            payload: ByteArray,
            isLastFrame: Boolean,
            context: AudioContext,
        ) = Unit

        override fun onFramesPerPacketChanged() {
            framesPerPacketChanges++
        }

        override fun updateConnectedStatus() = Unit
        override fun updateStatus(text: String) {
            statuses += text
        }

        override fun appendSystemMessage(message: String) {
            systemMessages += message
        }

        override fun getString(id: Int): String = "s$id"
        override fun getString(id: Int, vararg formatArgs: Any): String = "s$id"
    }

    private fun session(host: FakeHost = FakeHost()) = VoiceUdpSession(
        bandwidth = VoiceBandwidthController { _, _, _ -> },
        fallback = UdpFallbackController { 0L },
        host = host,
    )

    @Test
    fun useTcp_followsTheForceFlagAndTheFallbackState() {
        val session = session()
        assertFalse(session.useTcp)

        session.resetFallback(forceTcp = true)
        assertTrue(session.useTcp)
        assertTrue(session.isFallbackActive(forceTcp = false, live = true))
        assertFalse(session.isFallbackActive(forceTcp = false, live = false))

        session.resetFallback(forceTcp = false)
        assertFalse(session.useTcp)
        assertFalse(session.isFallbackActive(forceTcp = false, live = true))
    }

    @Test
    fun reconfigure_reportsAFrameDurationChangeOnlyOnce() {
        val host = FakeHost()
        host.appSettings = AppSettings(framesPerPacket = 4)
        val session = session(host)

        session.reconfigure()
        assertEquals(1, host.framesPerPacketChanges)

        session.reconfigure()
        assertEquals(1, host.framesPerPacketChanges)
    }

    @Test
    fun applyMaxBandwidth_withoutATransportOnlyUpdatesTheCap() {
        val host = FakeHost()
        val session = session(host)

        session.applyMaxBandwidth(0)
        session.applyMaxBandwidth(32_000)

        // No socket to reconfigure, and no other side effect leaks out.
        assertEquals(0, host.framesPerPacketChanges)
        assertTrue(host.statuses.isEmpty())
    }

    @Test
    fun connectionStats_areZeroBeforeTheSocketExists() {
        val stats = session().connectionStats()

        assertEquals(0, stats.good)
        assertEquals(0, stats.late)
        assertEquals(0, stats.lost)
        assertEquals(0, stats.resync)
    }

    @Test
    fun operationsWithoutATransportAreNoOps() {
        val host = FakeHost()
        val session = session(host)
        host.forceTcpValue = true

        session.startIfAllowed()
        session.playTunneled(byteArrayOf(1, 2, 3))
        session.onServerVersion(protobuf = true)
        session.applyQualityOfService(true)
        session.close()

        assertTrue(host.statuses.isEmpty())
        assertTrue(host.systemMessages.isEmpty())
    }
}
