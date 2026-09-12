package dev.woms.mumdroid.core.audio

/**
 * The time-axis (`frameNumber`) playback path of the jitter buffer: reorder,
 * drop late packets, conceal gaps and advance the play head.
 *
 * Split out of [VoiceJitterBuffer] because it is the largest and most
 * delicate part of the mix quantum — one wrong step here is audible as a
 * click or a stutter, so it is better read (and changed) on its own.
 */
internal class JitterTimedPlayback(
    private val minPreroll: Int,
    private val maxPreroll: Int,
    private val missLimit: Int = JitterPrerollPolicy.MISS_LIMIT,
) {
    /**
     * Official `fFadeIn` / `fFadeOut`: 10 ms sine window
     * (`sin(i * π / (2 * iFrameSizePerChannel))`).
     */
    private val fadeIn = FloatArray(OpusCodec.FRAME_SIZE_10MS) { i ->
        kotlin.math.sin(i * Math.PI / (2.0 * OpusCodec.FRAME_SIZE_10MS)).toFloat()
    }
    private val fadeOut = FloatArray(OpusCodec.FRAME_SIZE_10MS) { i ->
        fadeIn[OpusCodec.FRAME_SIZE_10MS - 1 - i]
    }

    /**
     * Pulls one quantum out of [s] into [acc].
     *
     * @return what was produced, so the caller can update the talk state: a
     *         concealed quantum still counts as "producing" (the stream is
     *         alive) but not as "real".
     */
    fun pull(
        session: Int,
        s: JitterSession,
        acc: IntArray,
        decoder: VoiceJitterBuffer.Decoder?,
    ): JitterPull {
        var produced = JitterPull.NONE
        var i = 0
        while (i < acc.size) {
            val left = s.leftover
            if (left != null && s.leftoverPos < left.size) {
                acc[i] += left[s.leftoverPos].toInt()
                s.leftoverPos++
                if (produced == JitterPull.NONE) {
                    produced = if (s.leftoverConceal) JitterPull.CONCEAL else JitterPull.REAL
                }
                i++
                continue
            }
            s.leftover = null
            s.leftoverPos = 0

            val head = s.playHead ?: break
            if (s.delayBoostRemaining > 0 && bufferedAhead(s) < s.targetPreroll &&
                s.timed.containsKey(head)
            ) {
                // Official jitter_buffer_update_delay: insert one concealment
                // without consuming the next real packet so this utterance
                // grows its buffer instead of waiting for the next talk spurt.
                s.delayBoostRemaining--
                s.leftover = conceal(session, decoder)
                s.leftoverConceal = true
                produced = if (produced == JitterPull.REAL) JitterPull.REAL else JitterPull.CONCEAL
                continue
            }
            val exact = s.timed.remove(head)
            if (exact != null) {
                val pcm = resolvePcm(session, exact, decoder) ?: run {
                    s.playHead = head + exact.spanFrames
                    continue
                }
                if (pcm.size >= OpusCodec.FRAME_SIZE_10MS) {
                    exact.spanSamples = pcm.size
                }
                s.lastDecodedSamples = pcm.size
                s.context = exact.context
                s.playHead = head + exact.spanFrames
                if (s.needFadeIn) {
                    if (pcm.size >= OpusCodec.FRAME_SIZE_10MS) applyFadeIn(pcm)
                    s.needFadeIn = false
                }
                if (exact.isLast) {
                    if (pcm.size >= OpusCodec.FRAME_SIZE_10MS) applyFadeOut(pcm)
                    s.ending = true
                    decoder?.reset(session)
                }
                s.leftover = pcm
                s.leftoverPos = 0
                s.leftoverConceal = false
                produced = JitterPull.REAL
                adaptPreroll(s, concealed = false)
                continue
            }

            val next = s.timed.firstEntry()
            if (next != null && next.key < head) {
                // Arrived after its slot went by.
                s.timed.pollFirstEntry()
                continue
            }
            if (next != null && next.key > head) {
                val gap = (next.key - head).toInt()
                if (gap > MAX_SKIPPABLE_GAP_FRAMES) {
                    // Too far ahead to conceal: jump the play head and start
                    // the next spurt cleanly rather than filling silence.
                    s.playHead = next.key
                    s.needFadeIn = true
                    continue
                }
            }
            // Official prepareSampleBuffer: while still alive, a miss is
            // opus_decode(null) so the decoder clock keeps moving. Hard
            // silence with a frozen playHead is what sounded like crackle.
            if (s.ending || s.missCount > missLimit) break
            val plc = conceal(session, decoder)
            if (s.missCount + 1 > missLimit) applyFadeOut(plc)
            s.playHead = head + 1
            s.leftover = plc
            s.leftoverPos = 0
            s.leftoverConceal = true
            produced = if (produced == JitterPull.REAL) JitterPull.REAL else JitterPull.CONCEAL
            adaptPreroll(s, concealed = true)
        }
        return produced
    }

    /**
     * One concealment frame. Both branches already produce a fresh array (the
     * decoder copies out of its scratch, the fallback allocates), and the
     * caller keeps it as the session's leftover and applies fades to it — so no
     * further defensive copy is needed here.
     */
    private fun conceal(session: Int, decoder: VoiceJitterBuffer.Decoder?): ShortArray =
        decoder?.conceal(session, OpusCodec.FRAME_SIZE_10MS)
            ?: ShortArray(OpusCodec.FRAME_SIZE_10MS)

    private fun applyFadeIn(pcm: ShortArray) {
        val n = minOf(pcm.size, fadeIn.size)
        for (i in 0 until n) {
            pcm[i] = (pcm[i] * fadeIn[i]).toInt().toShort()
        }
    }

    private fun applyFadeOut(pcm: ShortArray) {
        val n = minOf(pcm.size, fadeOut.size)
        val start = pcm.size - n
        for (i in 0 until n) {
            pcm[start + i] = (pcm[start + i] * fadeOut[i]).toInt().toShort()
        }
    }

    private fun resolvePcm(
        session: Int,
        packet: JitterPacket,
        decoder: VoiceJitterBuffer.Decoder?,
    ): ShortArray? {
        packet.pcm?.let { return it }
        val opus = packet.opus ?: return null
        return decoder?.decode(session, opus, packet.isLast)
    }

    /** Frames the queue holds ahead of the play head. */
    fun bufferedAhead(s: JitterSession): Int {
        if (s.timed.isEmpty()) return 0
        val last = s.timed.lastEntry() ?: return 0
        return JitterPrerollPolicy.bufferedAhead(
            playHead = s.playHead,
            lastFrame = last.key,
            lastSpanFrames = last.value.spanFrames,
        )
    }

    /** Length of the buffered span, independent of the play head. */
    private fun queuedSpan(s: JitterSession): Int {
        if (s.timed.isEmpty()) return 0
        val last = s.timed.lastEntry() ?: return 0
        return JitterPrerollPolicy.queuedSpan(
            firstFrame = s.timed.firstKey(),
            lastFrame = last.key,
            lastSpanFrames = last.value.spanFrames,
        )
    }

    /** Grows the preroll after concealment, shrinks it when the queue is full. */
    fun adaptPreroll(s: JitterSession, concealed: Boolean) {
        if (concealed && s.timed.isEmpty()) {
            // No queue to boost: still grow the target for the next spurt.
            s.targetPreroll = (s.targetPreroll + 1).coerceAtMost(maxPreroll)
            return
        }
        if (!concealed && s.timed.isEmpty()) return
        val result = JitterPrerollPolicy.adapt(
            concealed = concealed,
            current = s.targetPreroll,
            aheadFrames = bufferedAhead(s),
            queuedFrames = queuedSpan(s),
            min = minPreroll,
            max = maxPreroll,
        )
        s.targetPreroll = result.targetPreroll
        if (result.delayBoost) s.delayBoostRemaining = 1
    }

    private companion object {
        /** Gaps longer than this are jumped instead of concealed. */
        const val MAX_SKIPPABLE_GAP_FRAMES = 10
    }
}
