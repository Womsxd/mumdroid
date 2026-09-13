package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.JitterPacket
import dev.woms.mumdroid.core.audio.JitterPull
import dev.woms.mumdroid.core.audio.JitterSession
import dev.woms.mumdroid.core.audio.JitterTimedPlayback
import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceJitterBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time-axis playback loop directly: leftover carry-over, reorder/drop,
 * concealment, play-head advance, fades and the delay boost. [VoiceJitterBufferTest]
 * only exercises this through `mix`, so the individual branches are pinned here.
 */
class JitterTimedPlaybackTest {

    private class FakeDecoder : VoiceJitterBuffer.Decoder {
        var concealValue: Short = 9
        var concealCalls = 0
        var resetCalls = 0
        var decodeCalls = 0

        override fun decode(session: Int, payload: ByteArray, isLast: Boolean): ShortArray {
            decodeCalls++
            return ShortArray(OpusCodec.FRAME_SIZE_10MS) { 5 }
        }

        override fun conceal(session: Int, samples: Int): ShortArray {
            concealCalls++
            return ShortArray(samples) { concealValue }
        }

        override fun reset(session: Int) {
            resetCalls++
        }
    }

    private val playback = JitterTimedPlayback(minPreroll = 3, maxPreroll = 12, missLimit = 3)

    /** A started session with the fade-in already consumed so PCM compares exactly. */
    private fun session(playHead: Long) = JitterSession(initialPreroll = 3).apply {
        started = true
        this.playHead = playHead
        needFadeIn = false
    }

    private fun packet(
        frame: Long,
        pcm: ShortArray?,
        isLast: Boolean = false,
        spanSamples: Int = pcm?.size ?: OpusCodec.FRAME_SIZE_10MS,
    ) = JitterPacket(frame, pcm, null, isLast, spanSamples)

    @Test
    fun leftoverIsConsumedBeforeTheQueue() {
        val s = session(10)
        s.leftover = ShortArray(4) { 7 }
        s.leftoverConceal = false
        val acc = IntArray(4)

        val produced = playback.pull(1, s, acc, null)

        assertEquals(JitterPull.REAL, produced)
        assertArrayEquals(intArrayOf(7, 7, 7, 7), acc)
    }

    @Test
    fun concealedLeftoverIsReportedAsConcealment() {
        val s = session(10)
        s.leftover = ShortArray(4) { 2 }
        s.leftoverConceal = true
        val acc = IntArray(4)

        assertEquals(JitterPull.CONCEAL, playback.pull(1, s, acc, null))
        assertArrayEquals(intArrayOf(2, 2, 2, 2), acc)
    }

    @Test
    fun decodesThePacketAtThePlayHeadAndAdvances() {
        val s = session(10)
        s.timed[10] = packet(10, ShortArray(960) { 5 }, spanSamples = 960)
        val acc = IntArray(960)

        val produced = playback.pull(1, s, acc, null)

        assertEquals(JitterPull.REAL, produced)
        assertEquals(12L, s.playHead)
        assertTrue(s.timed.isEmpty())
        assertTrue(acc.all { it == 5 })
    }

    @Test
    fun aPartialFrameCarriesAcrossQuanta() {
        val s = session(10)
        val pcm = ShortArray(480) { (it + 1).toShort() }
        s.timed[10] = packet(10, pcm)

        val acc1 = IntArray(240)
        playback.pull(1, s, acc1, null)
        assertEquals(240, s.leftoverPos)

        val acc2 = IntArray(240)
        val produced = playback.pull(1, s, acc2, null)

        assertEquals(JitterPull.REAL, produced)
        assertEquals(pcm[240].toInt(), acc2[0])
        assertEquals(pcm[479].toInt(), acc2[239])
        assertEquals(480, s.leftoverPos)
        assertEquals(11L, s.playHead)
    }

    @Test
    fun packetsBehindThePlayHeadAreDropped() {
        val s = session(10)
        s.ending = true
        s.timed[5] = packet(5, ShortArray(OpusCodec.FRAME_SIZE_10MS) { 1 })
        val decoder = FakeDecoder()
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.NONE, produced)
        assertTrue(s.timed.isEmpty())
        assertEquals(0, decoder.concealCalls)
    }

    @Test
    fun aLargeGapJumpsTheHeadInsteadOfConcealing() {
        val s = session(10)
        s.timed[30] = packet(30, ShortArray(240) { 4 })
        val decoder = FakeDecoder()
        val acc = IntArray(240)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.REAL, produced)
        assertEquals("head jumps to 30, then advances by its 1-frame span", 31L, s.playHead)
        assertEquals("no concealment for the skipped span", 0, decoder.concealCalls)
        assertTrue(acc.all { it == 4 })
    }

    @Test
    fun aMissIsConcealedThroughTheDecoder() {
        val s = session(10)
        val decoder = FakeDecoder().apply { concealValue = 9 }
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.CONCEAL, produced)
        assertEquals(1, decoder.concealCalls)
        assertEquals(11L, s.playHead)
        assertTrue(acc.all { it == 9 })
    }

    @Test
    fun withoutADecoderAMissIsSilence() {
        val s = session(10)
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, null)

        assertEquals(JitterPull.CONCEAL, produced)
        assertEquals(11L, s.playHead)
        assertTrue(acc.all { it == 0 })
    }

    @Test
    fun theMissThatExceedsTheLimitIsFadedOut() {
        val s = session(10)
        s.missCount = 3 // one more miss (4) exceeds the limit of 3
        val decoder = FakeDecoder().apply { concealValue = 9 }
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.CONCEAL, produced)
        assertEquals(1, decoder.concealCalls)
        assertEquals("the concealment tail is faded to silence", 0, acc[OpusCodec.FRAME_SIZE_10MS - 1])
    }

    @Test
    fun pastTheLimitTheSessionStopsProducing() {
        val s = session(10)
        s.missCount = 4
        val decoder = FakeDecoder()
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.NONE, produced)
        assertEquals(0, decoder.concealCalls)
        assertEquals(10L, s.playHead)
    }

    @Test
    fun anEndingSessionStops() {
        val s = session(10)
        s.ending = true
        val decoder = FakeDecoder()

        assertEquals(JitterPull.NONE, playback.pull(1, s, IntArray(OpusCodec.FRAME_SIZE_10MS), decoder))
        assertEquals(0, decoder.concealCalls)
    }

    @Test
    fun theLastPacketFadesOutAndResetsTheDecoder() {
        val s = session(10)
        s.timed[10] = packet(10, ShortArray(OpusCodec.FRAME_SIZE_10MS) { 6 }, isLast = true)
        val decoder = FakeDecoder()
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.REAL, produced)
        assertTrue(s.ending)
        assertEquals(1, decoder.resetCalls)
        assertEquals(11L, s.playHead)
        assertEquals(0, acc[OpusCodec.FRAME_SIZE_10MS - 1])
    }

    @Test
    fun theDelayBoostInsertsAConcealmentWithoutConsumingThePacket() {
        val s = session(10)
        s.targetPreroll = 5
        s.delayBoostRemaining = 1
        s.timed[10] = packet(10, ShortArray(OpusCodec.FRAME_SIZE_10MS) { 7 })
        val decoder = FakeDecoder().apply { concealValue = 3 }
        val acc = IntArray(OpusCodec.FRAME_SIZE_10MS)

        val produced = playback.pull(1, s, acc, decoder)

        assertEquals(JitterPull.CONCEAL, produced)
        assertEquals(0, s.delayBoostRemaining)
        assertTrue("the real packet stays queued", s.timed.containsKey(10L))
        assertEquals(1, decoder.concealCalls)
        assertEquals(10L, s.playHead)
        assertTrue(acc.all { it == 3 })
    }

    @Test
    fun concealmentGrowsThePrerollTargetWhenTheQueueIsEmpty() {
        val s = session(10)
        s.targetPreroll = 3

        playback.pull(1, s, IntArray(OpusCodec.FRAME_SIZE_10MS), FakeDecoder())

        assertEquals(4, s.targetPreroll)
    }

    @Test
    fun bufferedAheadCountsThroughTheLastPacketsSpan() {
        val s = session(10)
        s.timed[12] = packet(12, ShortArray(OpusCodec.FRAME_SIZE_10MS) { 1 })
        s.timed[14] = packet(14, ShortArray(960) { 1 }, spanSamples = 960)

        assertEquals(6, playback.bufferedAhead(s))
    }

    @Test
    fun bufferedAheadIsZeroWithoutPackets() {
        assertFalse(session(10).let { playback.bufferedAhead(it) } > 0)
    }
}
