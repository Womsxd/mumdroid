package dev.woms.mumdroid.core.audio

import java.util.TreeMap
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Per-speaker playback jitter buffer with mix-down, mirroring the role of
 * the official client's Speex jitter buffer + [AudioOutput::mix].
 *
 * Two ingest paths:
 *  - [push] — arrival-order PCM (tests / untagged frames).
 *  - [pushTimed] / [pushEncoded] — official `frameNumber` time axis
 *    (`timestamp = iFrameSize * frameNumber`). Packets are reordered, late
 *    ones dropped, and gaps concealed at **playback** (not receive) time so
 *    the Opus decoder stays aligned.
 *
 * Incoming talk spurts are held until a preroll of audio has arrived so a
 * burst of late TCP/UDP packets does not play as a clump followed by
 * underrun clicks. Missing samples are silence or PLC; the audio thread
 * never `sleep`s. Idle sessions are reaped so the next utterance prerolls
 * again.
 */
class VoiceJitterBuffer(
    private val prerollFrames: Int = 2,
    private val maxQueuedFrames: Int = 16,
    private val idleTimeoutMs: Long = 400L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minTimedPreroll: Int = 3,
    private val maxTimedPreroll: Int = 12,
) {
    interface Decoder {
        fun decode(session: Int, payload: ByteArray, isLast: Boolean): ShortArray?
        fun conceal(session: Int, samples: Int): ShortArray?
        fun reset(session: Int) {}
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<Int, Session>()

    /**
     * Official `fFadeIn` / `fFadeOut`: 10 ms sine window
     * (`sin(i * π / (2 * iFrameSizePerChannel))`).
     */
    private val fadeIn = FloatArray(OpusCodec.FRAME_SIZE_10MS) { i ->
        sin(i * Math.PI / (2.0 * OpusCodec.FRAME_SIZE_10MS)).toFloat()
    }
    private val fadeOut = FloatArray(OpusCodec.FRAME_SIZE_10MS) { i ->
        fadeIn[OpusCodec.FRAME_SIZE_10MS - 1 - i]
    }

    private enum class Pull { None, Real, Conceal }

    @Volatile
    var decoder: Decoder? = null

    private class Packet(
        val frameNumber: Long,
        val pcm: ShortArray?,
        val opus: ByteArray?,
        val isLast: Boolean,
        var spanSamples: Int,
    ) {
        val spanFrames: Int
            get() = OpusCodec.tenMsFrames(spanSamples).coerceAtLeast(1)
    }

    private class Session(initialPreroll: Int) {
        val queued = ArrayDeque<ShortArray>()
        val timed = TreeMap<Long, Packet>()
        var leftover: ShortArray? = null
        var leftoverPos = 0
        var started = false
        var lastPushMs = 0L
        var missCount = 0
        var talking = false
        var timedMode = false
        var playHead: Long? = null
        var targetPreroll = initialPreroll
        var lastDecodedSamples = OpusCodec.FRAME_SIZE_10MS * 2
        var needFadeIn = true
        var ending = false
        var leftoverConceal = false
        /** Extra 10 ms PLC holds to grow delay in the current utterance. */
        var delayBoostRemaining = 0

        fun leftoverRemaining(): Int {
            val left = leftover ?: return 0
            return (left.size - leftoverPos).coerceAtLeast(0)
        }
    }

    /**
     * Official AudioOutputSpeech: after this many empty mix quantums the
     * stream is no longer alive (`iMissCount > 10` → Passive).
     */
    private val missLimit = 10

    /** Invoked when a speaker is reaped after [idleTimeoutMs] of silence. */
    @Volatile
    var onSessionEnded: ((Int) -> Unit)? = null

    /**
     * Playback-liveness talk state, matching `ClientUser::setTalking` from
     * [AudioOutputSpeech::needSamples]. Not packet-arrival.
     */
    @Volatile
    var onTalking: ((Int, Boolean) -> Unit)? = null

    /** Number of speakers currently buffered (including prerolling). */
    val sessionCount: Int
        get() = synchronized(lock) { sessions.size }

    /** Queues a decoded PCM frame for [session]. The array is copied. */
    fun push(session: Int, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val copy = pcm.copyOf()
        synchronized(lock) {
            val s = sessions.getOrPut(session) { Session(minTimedPreroll) }
            if (s.timedMode) {
                val head = s.playHead ?: 0L
                val guessed = s.timed.lastEntry()?.let { last ->
                    last.key + last.value.spanFrames
                } ?: head
                ingestTimed(s, Packet(guessed, copy, null, false, copy.size))
                return
            }
            while (s.queued.size >= maxQueuedFrames) {
                s.queued.removeFirst()
            }
            s.queued.addLast(copy)
            s.lastPushMs = clock()
            if (!s.started && s.queued.size >= prerollFrames) {
                s.started = true
            }
        }
    }

    /**
     * Queues already-decoded PCM stamped with official `frameNumber`
     * (10 ms units). Used by tests and as a fallback.
     */
    fun pushTimed(
        session: Int,
        frameNumber: Long,
        pcm: ShortArray,
        isLast: Boolean = false,
        spanFrames: Int = OpusCodec.tenMsFrames(pcm.size).coerceAtLeast(1),
    ) {
        if (pcm.isEmpty()) return
        val samples = spanFrames.coerceAtLeast(1) * OpusCodec.FRAME_SIZE_10MS
        synchronized(lock) {
            val s = sessions.getOrPut(session) { Session(minTimedPreroll) }
            ingestTimed(
                s,
                Packet(frameNumber, pcm.copyOf(), null, isLast, samples),
            )
        }
    }

    /**
     * Queues an encoded Opus packet for in-order decode at playback, matching
     * official `jitter_buffer_put` (`timestamp = iFrameSize * frameNumber`).
     */
    fun pushEncoded(
        session: Int,
        frameNumber: Long,
        opus: ByteArray,
        isLast: Boolean = false,
    ) {
        if (opus.isEmpty()) return
        val samples = OpusCodec.packetSampleCount(opus)
        synchronized(lock) {
            val s = sessions.getOrPut(session) { Session(minTimedPreroll) }
            ingestTimed(
                s,
                Packet(frameNumber, null, opus.copyOf(), isLast, samples),
            )
        }
    }

    private fun ingestTimed(s: Session, packet: Packet) {
        s.timedMode = true
        s.lastPushMs = clock()
        val head = s.playHead
        if (head != null && packet.frameNumber + packet.spanFrames <= head) {
            return
        }
        while (s.timed.size >= maxQueuedFrames) {
            s.timed.pollFirstEntry()
        }
        s.timed[packet.frameNumber] = packet
        maybeStartTimed(s)
    }

    private fun maybeStartTimed(s: Session) {
        if (s.started || s.timed.isEmpty()) return
        val first = s.timed.firstKey()
        val last = s.timed.lastEntry() ?: return
        val buffered = (last.key - first).toInt() + last.value.spanFrames
        if (s.timed.size >= prerollFrames || buffered >= s.targetPreroll) {
            s.started = true
            s.needFadeIn = true
            if (s.playHead == null) s.playHead = first
        }
    }

    /**
     * Mixes one playback quantum into [out]. Returns true when at least one
     * started session contributed samples (as opposed to pure silence).
     */
    fun mix(out: ShortArray): Boolean {
        if (out.isEmpty()) return false
        val acc = IntArray(out.size)
        val now = clock()
        var had = false
        val ended = ArrayList<Int>()
        val talkOn = ArrayList<Int>()
        val talkOff = ArrayList<Int>()
        synchronized(lock) {
            val it = sessions.entries.iterator()
            while (it.hasNext()) {
                val (id, s) = it.next()
                val idle = now - s.lastPushMs > idleTimeoutMs &&
                    s.queued.isEmpty() &&
                    s.timed.isEmpty() &&
                    s.leftoverRemaining() == 0
                if (idle) {
                    if (s.talking) talkOff.add(id)
                    decoder?.reset(id)
                    it.remove()
                    ended.add(id)
                    continue
                }
                if (!s.started) continue
                val pulled = if (s.timedMode) pullTimed(id, s, acc) else {
                    if (pullInto(s, acc)) Pull.Real else Pull.None
                }
                when (pulled) {
                    Pull.Real -> {
                        had = true
                        s.missCount = 0
                        if (!s.talking) {
                            s.talking = true
                            talkOn.add(id)
                        }
                    }
                    Pull.Conceal -> {
                        had = true
                        s.missCount++
                        if (s.talking && s.missCount > missLimit) {
                            s.talking = false
                            talkOff.add(id)
                        }
                    }
                    Pull.None -> {
                        s.missCount++
                        if (s.talking && s.missCount > missLimit) {
                            s.talking = false
                            talkOff.add(id)
                        }
                    }
                }
            }
        }
        for (i in out.indices) {
            out[i] = SoftLimiter.limit(acc[i].toDouble()).roundToInt().toShort()
        }
        val talkingTap = onTalking
        if (talkingTap != null) {
            for (id in talkOn) talkingTap(id, true)
            for (id in talkOff) talkingTap(id, false)
        }
        val tap = onSessionEnded
        if (tap != null) {
            for (id in ended) tap(id)
        }
        return had
    }

    /** Drops every session (used when playback stops). */
    fun clear() {
        synchronized(lock) { sessions.clear() }
    }

    private fun pullInto(s: Session, acc: IntArray): Boolean {
        var produced = false
        var i = 0
        while (i < acc.size) {
            val left = s.leftover
            if (left != null && s.leftoverPos < left.size) {
                acc[i] += left[s.leftoverPos].toInt()
                s.leftoverPos++
                produced = true
                i++
                continue
            }
            s.leftover = null
            s.leftoverPos = 0
            val next = s.queued.removeFirstOrNull() ?: break
            s.leftover = next
            s.leftoverPos = 0
        }
        return produced
    }

    private fun pullTimed(session: Int, s: Session, acc: IntArray): Pull {
        var produced = Pull.None
        var i = 0
        while (i < acc.size) {
            val left = s.leftover
            if (left != null && s.leftoverPos < left.size) {
                acc[i] += left[s.leftoverPos].toInt()
                s.leftoverPos++
                if (produced == Pull.None) {
                    produced = if (s.leftoverConceal) Pull.Conceal else Pull.Real
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
                val plc = (decoder?.conceal(session, OpusCodec.FRAME_SIZE_10MS)
                    ?: ShortArray(OpusCodec.FRAME_SIZE_10MS)).copyOf()
                s.leftover = plc
                s.leftoverPos = 0
                s.leftoverConceal = true
                produced = if (produced == Pull.Real) Pull.Real else Pull.Conceal
                continue
            }
            val exact = s.timed.remove(head)
            if (exact != null) {
                val pcm = resolvePcm(session, exact) ?: run {
                    s.playHead = head + exact.spanFrames
                    continue
                }
                if (pcm.size >= OpusCodec.FRAME_SIZE_10MS) {
                    exact.spanSamples = pcm.size
                }
                s.lastDecodedSamples = pcm.size
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
                produced = Pull.Real
                adaptPreroll(s, concealed = false)
                continue
            }

            val next = s.timed.firstEntry()
            if (next != null && next.key < head) {
                s.timed.pollFirstEntry()
                continue
            }
            if (next != null && next.key > head) {
                val gap = (next.key - head).toInt()
                if (gap > 10) {
                    s.playHead = next.key
                    s.needFadeIn = true
                    continue
                }
            }
            // Official prepareSampleBuffer: while still alive, a miss is
            // opus_decode(null) so the decoder clock keeps moving. Hard
            // silence with a frozen playHead is what sounded like crackle.
            if (s.ending || s.missCount > missLimit) break
            val plcSamples = OpusCodec.FRAME_SIZE_10MS
            val plc = (decoder?.conceal(session, plcSamples) ?: ShortArray(plcSamples)).copyOf()
            if (s.missCount + 1 > missLimit) applyFadeOut(plc)
            s.playHead = head + 1
            s.leftover = plc
            s.leftoverPos = 0
            s.leftoverConceal = true
            produced = if (produced == Pull.Real) Pull.Real else Pull.Conceal
            adaptPreroll(s, concealed = true)
        }
        return produced
    }

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

    private fun resolvePcm(session: Int, packet: Packet): ShortArray? {
        packet.pcm?.let { return it }
        val opus = packet.opus ?: return null
        return decoder?.decode(session, opus, packet.isLast)
    }

    /**
     * Coarse stand-in for official `jitter_buffer_update_delay`: grow the
     * preroll after concealment, shrink it when the queue is comfortably full.
     */
    private fun bufferedAhead(s: Session): Int {
        val head = s.playHead ?: return 0
        if (s.timed.isEmpty()) return 0
        val last = s.timed.lastEntry() ?: return 0
        return ((last.key - head).toInt() + last.value.spanFrames).coerceAtLeast(0)
    }

    private fun adaptPreroll(s: Session, concealed: Boolean) {
        if (concealed) {
            s.targetPreroll = (s.targetPreroll + 1).coerceAtMost(maxTimedPreroll)
            if (s.timed.isNotEmpty() && bufferedAhead(s) < s.targetPreroll) {
                s.delayBoostRemaining = 1
            }
            return
        }
        if (s.timed.isEmpty()) return
        val first = s.timed.firstKey()
        val last = s.timed.lastEntry() ?: return
        val buffered = (last.key - first).toInt() + last.value.spanFrames
        if (buffered > s.targetPreroll + 4) {
            s.targetPreroll = (s.targetPreroll - 1).coerceAtLeast(minTimedPreroll)
        }
    }
}
