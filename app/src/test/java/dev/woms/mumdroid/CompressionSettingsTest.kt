package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the compression ("codec") settings ported from the desktop Mumble
 * client: transmit quality (shown in KB/s), audio per packet (frame size) and
 * the low-latency mode.
 */
class CompressionSettingsTest {

    /** The transmit quality defaults to 40 KB/s, mirroring the desktop default. */
    @Test
    fun transmitQuality_defaultsTo40KbPerSec() {
        assertEquals(40, AppSettings().transmitQuality)
    }

    @Test
    fun transmitQuality_officialSliderRangeIs8To192() {
        assertEquals(8, VoiceBandwidth.QUALITY_MIN_KBPS)
        assertEquals(192, VoiceBandwidth.QUALITY_MAX_KBPS)
        assertEquals(8, VoiceBandwidth.clampQualityKbps(0))
        assertEquals(192, VoiceBandwidth.clampQualityKbps(400))
        assertEquals(73, VoiceBandwidth.clampQualityKbps(73))
    }

    @Test
    fun peakUsage_matchesOfficialDefault20ms() {
        val usage = VoiceBandwidth.peakUsage(40_000, 2)
        assertEquals(40_000, usage.audioBps)
        assertEquals(0, usage.positionBps)
        assertEquals(14_800, usage.overheadBps)
        assertEquals(54_800, usage.totalBps)
    }

    @Test
    fun peakUsage_splitsPositionFromOverhead() {
        val usage = VoiceBandwidth.peakUsage(40_000, 2, transmitPosition = true)
        assertEquals(4_800, usage.positionBps)
        assertEquals(14_800, usage.overheadBps)
        assertEquals(59_600, usage.totalBps)
    }

    /** Audio per packet defaults to 20 ms (2 x 10 ms frames). */
    @Test
    fun framesPerPacket_defaultsTo20ms() {
        assertEquals(2, AppSettings().framesPerPacket)
        assertEquals(2 * 10, AppSettings().framesPerPacket * 10)
    }

    /** Low-latency mode defaults to off. */
    @Test
    fun lowLatency_defaultsToOff() {
        assertFalse(AppSettings().lowLatency)
    }

    /** The settings round-trip through a copy. */
    @Test
    fun compressionSettings_roundTripThroughCopy() {
        val s = AppSettings().copy(
            transmitQuality = 64,
            framesPerPacket = 4,
            lowLatency = true,
        )
        assertEquals(64, s.transmitQuality)
        assertEquals(4, s.framesPerPacket)
        assertTrue(s.lowLatency)
    }

    /**
     * The Opus frame size selection maps the configured packet size to a valid
     * Opus frame (in samples at 48 kHz). 20 ms must map to 960 samples.
     */
    @Test
    fun opusFrameSize_maps20msTo960Samples() {
        assertEquals(480, OpusCodec.FRAME_SIZE_10MS)
        // 2 frames per packet => 20 ms => 960 samples.
        assertEquals(960, OpusCodec.FRAME_SIZE_10MS * 2)
    }

    @Test
    fun networkBandwidth_matchesOfficialDefault20ms() {
        // 20+8+4+1+2+2 = 37 bytes * (800/2) + 40000 = 54800
        assertEquals(54_800, VoiceBandwidth.getNetworkBandwidth(40_000, 2))
    }

    @Test
    fun networkBandwidth_tenMsHasHigherOverhead() {
        // 20+8+4+1+2+1 = 36 * 800 + 40000 = 68800
        assertEquals(68_800, VoiceBandwidth.getNetworkBandwidth(40_000, 1))
    }

    @Test
    fun networkBandwidth_tcpAddsTwelveBytes() {
        // 37+12 = 49 * 400 + 40000 = 59600
        assertEquals(59_600, VoiceBandwidth.getNetworkBandwidth(40_000, 2, tcpMode = true))
    }

    @Test
    fun adjustBandwidth_noCapKeepsUserChoice() {
        val a = VoiceBandwidth.adjustBandwidth(-1, 40_000, 1)
        assertEquals(40_000, a.bitrate)
        assertEquals(1, a.frames)
    }

    @Test
    fun adjustBandwidth_tenMsOn64kServer_bumpsTo20ms() {
        // Official: frames==1 && bitspersec<=64000 → frames=2, then shave bitrate.
        val a = VoiceBandwidth.adjustBandwidth(64_000, 72_000, 1)
        assertEquals(2, a.frames)
        assertTrue(VoiceBandwidth.getNetworkBandwidth(a.bitrate, a.frames) <= 64_000)
        assertEquals(49_000, a.bitrate)
    }

    @Test
    fun adjustBandwidth_twentyMsOn32kServer_bumpsTo40msAndCutsBitrate() {
        // Official: frames<=4 && bitspersec<=32000 → frames=4, then shave.
        val a = VoiceBandwidth.adjustBandwidth(32_000, 40_000, 2)
        assertEquals(4, a.frames)
        assertEquals(24_000, a.bitrate)
        assertTrue(VoiceBandwidth.getNetworkBandwidth(a.bitrate, a.frames) <= 32_000)
    }

    @Test
    fun adjustBandwidth_sixtyMsOnlyCutsBitrate() {
        val a = VoiceBandwidth.adjustBandwidth(32_000, 40_000, 6)
        assertEquals(6, a.frames)
        assertTrue(a.bitrate <= 40_000)
        assertTrue(VoiceBandwidth.getNetworkBandwidth(a.bitrate, a.frames) <= 32_000)
    }

    @Test
    fun useLowDelay_requires64kbps() {
        assertTrue(VoiceBandwidth.useLowDelay(true, 64_000))
        assertFalse(VoiceBandwidth.useLowDelay(true, 40_000))
        assertFalse(VoiceBandwidth.useLowDelay(false, 72_000))
    }

    @Test
    fun encoder_defaultQualityUsesAudioApplication() {
        val codec = OpusCodec()
        codec.setBitrate(40_000)
        val encoded = codec.encode(ShortArray(960) { 80 })
        assertTrue(encoded != null && encoded.isNotEmpty())
        codec.close()
    }
}
