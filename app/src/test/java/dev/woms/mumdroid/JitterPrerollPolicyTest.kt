package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.JitterPrerollPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jitter buffer's buffering rules: when a talk spurt may start and how the
 * preroll target adapts. Previously tangled into `maybeStartTimed` /
 * `adaptPreroll`, where the only way to check a threshold was the audio
 * thread's behaviour.
 */
class JitterPrerollPolicyTest {

    @Test
    fun missLimit_matchesTheOfficialClient() {
        assertEquals(10, JitterPrerollPolicy.MISS_LIMIT)
    }

    @Test
    fun bufferedAhead_isMeasuredFromThePlayHead() {
        // Play head 10, last packet at 14 spanning 2 frames -> 6 frames ahead.
        assertEquals(
            6,
            JitterPrerollPolicy.bufferedAhead(playHead = 10L, lastFrame = 14L, lastSpanFrames = 2),
        )
        // Before playback starts the head is null, so nothing is ahead of it.
        assertEquals(
            0,
            JitterPrerollPolicy.bufferedAhead(playHead = null, lastFrame = 14L, lastSpanFrames = 2),
        )
        // An empty queue reports zero rather than a negative span.
        assertEquals(
            0,
            JitterPrerollPolicy.bufferedAhead(playHead = 10L, lastFrame = null, lastSpanFrames = 0),
        )
        // A play head ahead of the queue is clamped, never negative.
        assertEquals(
            0,
            JitterPrerollPolicy.bufferedAhead(playHead = 20L, lastFrame = 2L, lastSpanFrames = 2),
        )
    }

    @Test
    fun queuedSpan_ignoresThePlayHead() {
        // 8..14 plus the last packet's own 2 frames spans 8 frames.
        assertEquals(
            8,
            JitterPrerollPolicy.queuedSpan(firstFrame = 8L, lastFrame = 14L, lastSpanFrames = 2),
        )
        assertEquals(0, JitterPrerollPolicy.queuedSpan(firstFrame = null, lastFrame = null, lastSpanFrames = 0))
        // A single packet is worth its own span only.
        assertEquals(2, JitterPrerollPolicy.queuedSpan(firstFrame = 5L, lastFrame = 5L, lastSpanFrames = 2))
    }

    @Test
    fun shouldStart_acceptsEitherEnoughPacketsOrEnoughTime() {
        // Two packets is enough even if they are short.
        assertTrue(JitterPrerollPolicy.shouldStart(queuedPackets = 2, prerollFrames = 2, bufferedFrames = 1, targetPreroll = 3))
        // A single long packet is enough once it covers the target.
        assertTrue(JitterPrerollPolicy.shouldStart(queuedPackets = 1, prerollFrames = 2, bufferedFrames = 3, targetPreroll = 3))
        assertFalse(JitterPrerollPolicy.shouldStart(queuedPackets = 1, prerollFrames = 2, bufferedFrames = 2, targetPreroll = 3))
    }

    @Test
    fun adapt_concealmentGrowsTheTargetUpToTheCeiling() {
        val grown = JitterPrerollPolicy.adapt(concealed = true, current = 3, aheadFrames = 1, queuedFrames = 1, min = 3, max = 12)
        assertEquals(4, grown.targetPreroll)
        // The queue is below the new target, so one concealment is inserted now.
        assertTrue(grown.delayBoost)

        val atCeiling = JitterPrerollPolicy.adapt(concealed = true, current = 12, aheadFrames = 0, queuedFrames = 0, min = 3, max = 12)
        assertEquals(12, atCeiling.targetPreroll)
        assertTrue(atCeiling.delayBoost)
    }

    @Test
    fun adapt_noBoostWhenTheQueueAlreadyCoversTheTarget() {
        val result = JitterPrerollPolicy.adapt(concealed = true, current = 3, aheadFrames = 9, queuedFrames = 9, min = 3, max = 12)
        assertEquals(4, result.targetPreroll)
        assertFalse(result.delayBoost)
    }

    @Test
    fun adapt_theTwoBranchesLookAtDifferentDepths() {
        // Concealment boost reads the depth in front of the play head; the
        // shrink reads the whole buffered span. A stale play head makes the
        // two differ, and each branch must use its own.
        val boost = JitterPrerollPolicy.adapt(
            concealed = true,
            current = 3,
            aheadFrames = 2,
            queuedFrames = 99,
            min = 3,
            max = 12,
        )
        assertEquals(4, boost.targetPreroll)
        assertTrue(boost.delayBoost)

        val shrink = JitterPrerollPolicy.adapt(
            concealed = false,
            current = 3,
            aheadFrames = 99,
            queuedFrames = 2,
            min = 3,
            max = 12,
        )
        assertEquals(3, shrink.targetPreroll)
        assertFalse(shrink.delayBoost)
    }

    @Test
    fun adapt_shrinksOnlyWithRealSlack() {
        // buffered must exceed current + SHRINK_SLACK (4).
        val noShrink = JitterPrerollPolicy.adapt(concealed = false, current = 6, aheadFrames = 10, queuedFrames = 10, min = 3, max = 12)
        assertEquals(6, noShrink.targetPreroll)
        assertFalse(noShrink.delayBoost)

        val shrunk = JitterPrerollPolicy.adapt(concealed = false, current = 6, aheadFrames = 11, queuedFrames = 11, min = 3, max = 12)
        assertEquals(5, shrunk.targetPreroll)
        assertFalse(shrunk.delayBoost)
    }

    @Test
    fun adapt_neverShrinksBelowTheFloor() {
        val floor = JitterPrerollPolicy.adapt(concealed = false, current = 3, aheadFrames = 99, queuedFrames = 99, min = 3, max = 12)
        assertEquals(3, floor.targetPreroll)
    }
}
