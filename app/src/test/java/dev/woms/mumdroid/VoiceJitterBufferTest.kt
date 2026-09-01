package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceJitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceJitterBufferTest {

    @Test
    fun preroll_holdsUntilTwoFrames() {
        val jb = VoiceJitterBuffer(prerollFrames = 2)
        jb.push(1, shortArrayOf(100, 100, 100, 100))
        val out = ShortArray(4)
        assertFalse(jb.mix(out))
        assertTrue(out.all { it == 0.toShort() })

        jb.push(1, shortArrayOf(200, 200, 200, 200))
        assertTrue(jb.mix(out))
        assertEquals(100.toShort(), out[0])
    }

    @Test
    fun mix_addsTwoSpeakers() {
        val jb = VoiceJitterBuffer(prerollFrames = 1)
        jb.push(1, ShortArray(4) { 1000 })
        jb.push(2, ShortArray(4) { 2000 })
        val out = ShortArray(4)
        assertTrue(jb.mix(out))
        assertEquals(3000.toShort(), out[0])
    }

    @Test
    fun underrun_fillsSilence() {
        val jb = VoiceJitterBuffer(prerollFrames = 1)
        jb.push(1, shortArrayOf(7, 8))
        val out = ShortArray(4)
        assertTrue(jb.mix(out))
        assertEquals(7.toShort(), out[0])
        assertEquals(8.toShort(), out[1])
        assertEquals(0.toShort(), out[2])
        assertEquals(0.toShort(), out[3])
    }

    @Test
    fun overflow_dropsOldest() {
        val jb = VoiceJitterBuffer(prerollFrames = 1, maxQueuedFrames = 2)
        jb.push(1, ShortArray(2) { 1 })
        jb.push(1, ShortArray(2) { 2 })
        jb.push(1, ShortArray(2) { 3 })
        val first = ShortArray(2)
        jb.mix(first)
        assertEquals(2.toShort(), first[0])
        val second = ShortArray(2)
        jb.mix(second)
        assertEquals(3.toShort(), second[0])
    }

    @Test
    fun leftover_spansQuantums() {
        val jb = VoiceJitterBuffer(prerollFrames = 1)
        jb.push(1, shortArrayOf(1, 2, 3, 4))
        val a = ShortArray(2)
        val b = ShortArray(2)
        assertTrue(jb.mix(a))
        assertTrue(jb.mix(b))
        assertEquals(1.toShort(), a[0])
        assertEquals(2.toShort(), a[1])
        assertEquals(3.toShort(), b[0])
        assertEquals(4.toShort(), b[1])
    }

    @Test
    fun talking_followsPlaybackLiveness() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val jb = VoiceJitterBuffer(prerollFrames = 1)
        jb.onTalking = { id, on -> events += id to on }
        jb.push(3, ShortArray(4) { 10 })
        val out = ShortArray(4)
        jb.mix(out)
        assertEquals(listOf(3 to true), events)

        repeat(10) { jb.mix(out) }
        assertEquals(listOf(3 to true), events)

        jb.mix(out)
        assertEquals(listOf(3 to true, 3 to false), events)
    }

    @Test
    fun idleSession_isReaped() {
        var now = 1_000L
        val jb = VoiceJitterBuffer(prerollFrames = 1, idleTimeoutMs = 50, clock = { now })
        jb.push(7, ShortArray(2) { 9 })
        val out = ShortArray(2)
        jb.mix(out)
        now += 100
        jb.mix(out)
        assertEquals(0, jb.sessionCount)
    }

    @Test
    fun timed_official20msSequence_playsWithoutInsertedGap() {
        val jb = VoiceJitterBuffer(prerollFrames = 1, minTimedPreroll = 1)
        jb.pushTimed(1, 0L, ShortArray(4) { 1 }, spanFrames = 2)
        jb.pushTimed(1, 2L, ShortArray(4) { 2 }, spanFrames = 2)
        val first = ShortArray(4)
        val second = ShortArray(4)
        assertTrue(jb.mix(first))
        assertTrue(jb.mix(second))
        assertEquals(1.toShort(), first[0])
        assertEquals(2.toShort(), second[0])
    }

    @Test
    fun timed_reordersOutOfOrderPackets() {
        val jb = VoiceJitterBuffer(prerollFrames = 2, minTimedPreroll = 3)
        jb.pushTimed(1, 2L, ShortArray(4) { 20 }, spanFrames = 2)
        jb.pushTimed(1, 0L, ShortArray(4) { 10 }, spanFrames = 2)
        val first = ShortArray(4)
        val second = ShortArray(4)
        assertTrue(jb.mix(first))
        assertTrue(jb.mix(second))
        assertEquals(10.toShort(), first[0])
        assertEquals(20.toShort(), second[0])
    }

    @Test
    fun timed_latePacketAfterPlayhead_isDropped() {
        val jb = VoiceJitterBuffer(prerollFrames = 1, minTimedPreroll = 1)
        jb.pushTimed(1, 0L, ShortArray(4) { 1 }, spanFrames = 2)
        val first = ShortArray(4)
        assertTrue(jb.mix(first))
        jb.pushTimed(1, 0L, ShortArray(4) { 99 }, spanFrames = 2)
        jb.pushTimed(1, 2L, ShortArray(4) { 2 }, spanFrames = 2)
        val second = ShortArray(4)
        assertTrue(jb.mix(second))
        assertEquals(2.toShort(), second[0])
    }

    @Test
    fun encode_usesPcmLengthNotStaleFrameSize() {
        val codec = OpusCodec()
        codec.setFrameSize(1920)
        val encoded = codec.encode(ShortArray(960) { 100 })
        assertTrue(encoded != null && encoded.isNotEmpty())
        val decoded = codec.decode(encoded!!)
        assertTrue(decoded != null && decoded.size == 960)
        codec.close()
    }

    @Test
    fun packetSampleCount_matchesEncoded20ms() {
        val codec = OpusCodec()
        codec.setFrameSize(960)
        val encoded = codec.encode(ShortArray(960) { 50 })!!
        assertEquals(960, OpusCodec.packetSampleCount(encoded))
        codec.close()
    }

    @Test
    fun timed_underrun_usesPlcNotHardSilence() {
        var concealed = 0
        val jb = VoiceJitterBuffer(prerollFrames = 1, minTimedPreroll = 1)
        jb.decoder = object : VoiceJitterBuffer.Decoder {
            override fun decode(session: Int, payload: ByteArray, isLast: Boolean) = null
            override fun conceal(session: Int, samples: Int): ShortArray {
                concealed += samples
                return ShortArray(samples) { 7 }
            }
        }
        jb.pushTimed(1, 0L, ShortArray(4) { 1 }, spanFrames = 2)
        val first = ShortArray(4)
        assertTrue(jb.mix(first))
        val second = ShortArray(4)
        assertTrue(jb.mix(second))
        assertEquals(7.toShort(), second[0])
        assertTrue(concealed >= OpusCodec.FRAME_SIZE_10MS)
    }

    @Test
    fun timed_fadeIn_softensFirstTenMs() {
        val jb = VoiceJitterBuffer(prerollFrames = 1, minTimedPreroll = 1)
        jb.pushTimed(1, 0L, ShortArray(OpusCodec.FRAME_SIZE_10MS) { 10_000 }, spanFrames = 1)
        val out = ShortArray(OpusCodec.FRAME_SIZE_10MS)
        assertTrue(jb.mix(out))
        assertTrue(out[0] < out[out.lastIndex])
        assertTrue(out[0] < 2_000)
    }
}
