package dev.woms.mumdroid.core.audio

/**
 * The buffering decisions of [VoiceJitterBuffer], kept free of state so the
 * official client's rules can be read (and tested) in one place:
 *
 *  - a talk spurt only starts playing once a preroll has arrived;
 *  - concealment grows the preroll, a comfortably full queue shrinks it again
 *    (a coarse stand-in for `jitter_buffer_update_delay`).
 */
internal object JitterPrerollPolicy {

    /**
     * Official `AudioOutputSpeech`: after this many empty mix quantums the
     * stream is no longer alive (`iMissCount > 10` -> Passive).
     */
    const val MISS_LIMIT = 10

    /**
     * How many 10 ms frames the queue holds ahead of the play head. The queue
     * is a gap-free span, so the last packet's own span counts.
     */
    fun bufferedAhead(
        playHead: Long?,
        lastFrame: Long?,
        lastSpanFrames: Int,
    ): Int {
        val head = playHead ?: return 0
        if (lastFrame == null) return 0
        return ((lastFrame - head).toInt() + lastSpanFrames).coerceAtLeast(0)
    }

    /**
     * Length of the buffered span itself (first packet -> end of the last
     * one). Used before playback starts, when there is no play head yet, and
     * for the delay adaptation, which reasons about queue depth.
     */
    fun queuedSpan(firstFrame: Long?, lastFrame: Long?, lastSpanFrames: Int): Int {
        if (firstFrame == null || lastFrame == null) return 0
        return ((lastFrame - firstFrame).toInt() + lastSpanFrames).coerceAtLeast(0)
    }

    /**
     * [VoiceJitterBuffer.maybeStartTimed]: enough packets *or* enough buffered
     * time starts the spurt. Packet count alone would start a spurt of very
     * short frames too early.
     */
    fun shouldStart(
        queuedPackets: Int,
        prerollFrames: Int,
        bufferedFrames: Int,
        targetPreroll: Int,
    ): Boolean = queuedPackets >= prerollFrames || bufferedFrames >= targetPreroll

    /** The next preroll target after one mix quantum. */
    data class AdaptResult(
        val targetPreroll: Int,
        /** Whether one concealment should be inserted to grow the delay now. */
        val delayBoost: Boolean,
    )

    /**
     * @param aheadFrames frames in front of the play head — decides whether a
     *        concealment must be inserted to grow the delay right now.
     * @param queuedFrames the whole buffered span (play head may be stale) —
     *        decides whether the queue is deep enough to shrink the target.
     */
    fun adapt(
        concealed: Boolean,
        current: Int,
        aheadFrames: Int,
        queuedFrames: Int,
        min: Int,
        max: Int,
    ): AdaptResult {
        if (concealed) {
            val grown = (current + 1).coerceAtMost(max)
            // Only boost when the spurt actually sits below the new target,
            // otherwise it would stall on an already healthy buffer.
            return AdaptResult(grown, delayBoost = aheadFrames < grown)
        }
        // Shrink only once the queue has real slack, to avoid oscillating
        // between two targets on every quantum.
        return if (queuedFrames > current + SHRINK_SLACK) {
            AdaptResult((current - 1).coerceAtLeast(min), delayBoost = false)
        } else {
            AdaptResult(current, delayBoost = false)
        }
    }

    /** Frames of slack required before the preroll target is lowered. */
    const val SHRINK_SLACK = 4
}
