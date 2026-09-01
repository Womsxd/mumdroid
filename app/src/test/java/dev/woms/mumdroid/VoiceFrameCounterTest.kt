package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceFrameCounter
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFrameCounterTest {

    @Test
    fun twentyMsPackets_stampEvenFrameNumbers() {
        val counter = VoiceFrameCounter()
        assertEquals(0L, counter.allocate(2))
        assertEquals(2L, counter.allocate(2))
        assertEquals(4L, counter.allocate(2))
        assertEquals(6L, counter.value)
    }

    @Test
    fun tenMsPackets_incrementByOne() {
        val counter = VoiceFrameCounter()
        assertEquals(0L, counter.allocate(1))
        assertEquals(1L, counter.allocate(1))
        assertEquals(2L, counter.allocate(1))
    }

    @Test
    fun fortyMsPackets_incrementByFour() {
        val counter = VoiceFrameCounter()
        assertEquals(0L, counter.allocate(4))
        assertEquals(4L, counter.allocate(4))
    }

    @Test
    fun idleFiveSeconds_resetsLikeOfficialISilentFrames() {
        var now = 1_000L
        val counter = VoiceFrameCounter(clock = { now }, resetAfterMs = 5_000L)
        assertEquals(0L, counter.allocate(2))
        assertEquals(2L, counter.allocate(2))
        now += 5_001L
        assertEquals(0L, counter.allocate(2))
    }

    @Test
    fun shortPause_keepsCounting() {
        var now = 1_000L
        val counter = VoiceFrameCounter(clock = { now }, resetAfterMs = 5_000L)
        counter.allocate(2)
        now += 400L
        assertEquals(2L, counter.allocate(2))
    }

    @Test
    fun tenMsFrames_fromPcmSize() {
        assertEquals(2, OpusCodec.tenMsFrames(960))
        assertEquals(4, OpusCodec.tenMsFrames(1920))
        assertEquals(1, OpusCodec.tenMsFrames(480))
    }
}
