package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.net.UdpVoiceManager
import dev.woms.mumdroid.service.VoiceBandwidthController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBandwidthControllerTest {

    private class Recorder {
        val notices = mutableListOf<Triple<Int, Int, Int>>()

        fun controller() = VoiceBandwidthController { maxKbps, adjustedKbps, framesMs ->
            notices.add(Triple(maxKbps, adjustedKbps, framesMs))
        }
    }

    private fun settings(qualityKbps: Int = 64, frames: Int = 2) = AppSettings(
        transmitQuality = qualityKbps,
        framesPerPacket = frames,
    )

    @Test
    fun onServerMaxBandwidth_rejectsNonPositive() {
        val c = Recorder().controller()
        assertFalse(c.onServerMaxBandwidth(0))
        assertFalse(c.onServerMaxBandwidth(-100))
        assertTrue(c.onServerMaxBandwidth(64_000))
        assertEquals(64_000, c.serverMaxBandwidthBps)
    }

    @Test
    fun resetTo_restoresRawSettingsAndClearsCap() {
        val c = Recorder().controller()
        c.onServerMaxBandwidth(32_000)
        c.reconfigure(settings(), useTcp = false, udp = null)
        c.resetTo(settings(qualityKbps = 96, frames = 4))
        assertEquals(4, c.effectiveFramesPerPacket)
        assertEquals(96_000, c.effectiveBitrateBps)
        assertEquals(0, c.serverMaxBandwidthBps)
    }

    @Test
    fun reconfigure_withoutCap_appliesWantedAndSkipsNotice() {
        val rec = Recorder()
        val c = rec.controller()
        // Establish the baseline the way connect does (applyInitialSettings ->
        // resetTo) so "changed" means changed relative to the applied settings.
        c.resetTo(settings(qualityKbps = 96, frames = 4))
        val framesChanged = c.reconfigure(settings(qualityKbps = 96, frames = 4), useTcp = false, udp = null)
        assertFalse(framesChanged)
        assertEquals(96_000, c.effectiveBitrateBps)
        assertEquals(4, c.effectiveFramesPerPacket)
        assertTrue(rec.notices.isEmpty())
    }

    @Test
    fun reconfigure_underCap_enlargesFramesAndNotifiesOnce() {
        val rec = Recorder()
        val c = rec.controller()
        c.resetTo(settings(qualityKbps = 192, frames = 2))
        c.onServerMaxBandwidth(32_000)

        val framesChanged = c.reconfigure(settings(qualityKbps = 192, frames = 2), useTcp = false, udp = null)
        // Official rule: ≤ 32 kbit/s caps first enlarge the packet to 4 frames.
        assertTrue(framesChanged)
        assertEquals(4, c.effectiveFramesPerPacket)
        assertTrue(c.effectiveBitrateBps in 8_000..32_000)
        assertEquals(1, rec.notices.size)
        assertEquals(32, rec.notices[0].first)
        assertEquals(40, rec.notices[0].third)
        // Re-running with the same outcome must not repeat the notice.
        c.reconfigure(settings(qualityKbps = 192, frames = 2), useTcp = false, udp = null)
        assertEquals(1, rec.notices.size)
    }

    @Test
    fun reconfigure_appliesEffectiveSettingsToUdpManager() {
        val udp = UdpVoiceManager("127.0.0.1", 64738)
        val c = Recorder().controller()
        c.resetTo(settings(qualityKbps = 96, frames = 4))
        c.reconfigure(settings(qualityKbps = 96, frames = 4), useTcp = false, udp = udp)
        assertEquals(96_000, udp.bitrate)
        assertEquals(4, udp.framesPerPacket)
        udp.close()
    }

    @Test
    fun currentBandwidthBps_includesTcpOverhead() {
        val c = Recorder().controller()
        c.resetTo(settings(qualityKbps = 64, frames = 2))
        val udpBps = c.currentBandwidthBps(useTcp = false)
        val tcpBps = c.currentBandwidthBps(useTcp = true)
        assertEquals(VoiceBandwidth.getNetworkBandwidth(64_000, 2, tcpMode = false), udpBps)
        assertEquals(VoiceBandwidth.getNetworkBandwidth(64_000, 2, tcpMode = true), tcpBps)
        assertTrue(tcpBps > udpBps)
    }
}
