package dev.woms.mumdroid.core.audio

import android.os.SystemClock
import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.model.TalkState
import java.util.TreeMap
import kotlin.math.roundToInt

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
    // Monotonic like official QElapsedTimer / UdpVoiceManager. Wall time
    // would let an NTP step exceed idleTimeoutMs and reap a live speaker.
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val minTimedPreroll: Int = 3,
    private val maxTimedPreroll: Int = 12,
) {
    interface Decoder {
        fun decode(session: Int, payload: ByteArray, isLast: Boolean): ShortArray?
        fun conceal(session: Int, samples: Int): ShortArray?
        fun reset(session: Int) {}
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<Int, JitterSession>()

    /** Time-axis playback (reorder / conceal / advance the play head). */
    private val timed = JitterTimedPlayback(
        minPreroll = minTimedPreroll,
        maxPreroll = maxTimedPreroll,
    )



    @Volatile
    var decoder: Decoder? = null

    /** Official `AudioOutputSpeech`: miss count at which a speaker goes passive. */
    private val missLimit = JitterPrerollPolicy.MISS_LIMIT

    /** Invoked when a speaker is reaped after [idleTimeoutMs] of silence. */
    @Volatile
    var onSessionEnded: ((Int) -> Unit)? = null

    /**
     * Playback-liveness talk state, matching `ClientUser::setTalking` from
     * [AudioOutputSpeech::needSamples]. Not packet-arrival.
     *
     * The state is [TalkState.PASSIVE] when the stream goes quiet, otherwise it
     * is derived from the packet context (official
     * `AudioOutputSpeech::prepareSampleBuffer`), so a whispering speaker can be
     * distinguished from a talking one.
     */
    @Volatile
    var onTalking: ((Int, TalkState) -> Unit)? = null

    /** Number of speakers currently buffered (including prerolling). */
    val sessionCount: Int
        get() = synchronized(lock) { sessions.size }

    /** Queues a decoded PCM frame for [session]. The array is copied. */
    fun push(session: Int, pcm: ShortArray, context: AudioContext = AudioContext.NORMAL) {
        if (pcm.isEmpty()) return
        val copy = pcm.copyOf()
        synchronized(lock) {
            val s = sessions.getOrPut(session) { JitterSession(minTimedPreroll) }
            s.context = context
            if (s.timedMode) {
                val head = s.playHead ?: 0L
                val guessed = s.timed.lastEntry()?.let { last ->
                    last.key + last.value.spanFrames
                } ?: head
                ingestTimed(s, JitterPacket(guessed, copy, null, false, copy.size, context))
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
        context: AudioContext = AudioContext.NORMAL,
    ) {
        if (pcm.isEmpty()) return
        val samples = spanFrames.coerceAtLeast(1) * OpusCodec.FRAME_SIZE_10MS
        synchronized(lock) {
            val s = sessions.getOrPut(session) { JitterSession(minTimedPreroll) }
            ingestTimed(
                s,
                JitterPacket(frameNumber, pcm.copyOf(), null, isLast, samples, context),
            )
        }
    }

    /**
     * Queues an encoded Opus packet for in-order decode at playback, matching
     * official `jitter_buffer_put` (`timestamp = iFrameSize * frameNumber`).
     *
     * Takes ownership of [opus] instead of copying it: every caller already
     * hands over a buffer built for this one packet (the framing layer copies
     * the payload out of the received datagram), and the decode happens later
     * from this same array. Do not pass a buffer that is reused or mutated
     * afterwards.
     */
    fun pushEncoded(
        session: Int,
        frameNumber: Long,
        opus: ByteArray,
        isLast: Boolean = false,
        context: AudioContext = AudioContext.NORMAL,
    ) {
        if (opus.isEmpty()) return
        val samples = OpusCodec.packetSampleCount(opus)
        synchronized(lock) {
            val s = sessions.getOrPut(session) { JitterSession(minTimedPreroll) }
            ingestTimed(
                s,
                JitterPacket(frameNumber, null, opus, isLast, samples, context),
            )
        }
    }

    private fun ingestTimed(s: JitterSession, packet: JitterPacket) {
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

    private fun maybeStartTimed(s: JitterSession) {
        if (s.started || s.timed.isEmpty()) return
        val first = s.timed.firstKey()
        val last = s.timed.lastEntry() ?: return
        // No play head yet: the preroll decision is about the span we hold.
        val buffered = JitterPrerollPolicy.queuedSpan(
            firstFrame = first,
            lastFrame = last.key,
            lastSpanFrames = last.value.spanFrames,
        )
        if (JitterPrerollPolicy.shouldStart(s.timed.size, prerollFrames, buffered, s.targetPreroll)) {
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
        val talkOn = ArrayList<Pair<Int, TalkState>>()
        val talkOff = ArrayList<Int>()
        synchronized(lock) {
            val it = sessions.entries.iterator()
            while (it.hasNext()) {
                val (id, s) = it.next()
                val idle = now - s.lastPushMs > idleTimeoutMs && s.isDrained()
                if (idle) {
                    if (s.talking) {
                        s.talking = false
                        s.reportedState = TalkState.PASSIVE
                        talkOff.add(id)
                    }
                    decoder?.reset(id)
                    it.remove()
                    ended.add(id)
                    continue
                }
                if (!s.started) continue
                val pulled = if (s.timedMode) timed.pull(id, s, acc, decoder) else {
                    if (pullInto(s, acc)) JitterPull.REAL else JitterPull.NONE
                }
                when (pulled) {
                    JitterPull.REAL -> {
                        had = true
                        s.missCount = 0
                        val state = TalkState.fromContext(s.context)
                        if (!s.talking || s.reportedState != state) {
                            s.talking = true
                            s.reportedState = state
                            talkOn.add(id to state)
                        }
                    }
                    JitterPull.CONCEAL -> {
                        had = true
                        s.missCount++
                        if (s.talking && s.missCount > missLimit) {
                            s.talking = false
                            s.reportedState = TalkState.PASSIVE
                            talkOff.add(id)
                        }
                    }
                    JitterPull.NONE -> {
                        s.missCount++
                        if (s.talking && s.missCount > missLimit) {
                            s.talking = false
                            s.reportedState = TalkState.PASSIVE
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
            for ((id, state) in talkOn) talkingTap(id, state)
            for (id in talkOff) talkingTap(id, TalkState.PASSIVE)
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

    /** Arrival-order playback path: straight FIFO, silence on underrun. */
    private fun pullInto(s: JitterSession, acc: IntArray): Boolean {
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
}

/** What one playback quantum managed to pull out of a session. */
internal enum class JitterPull { NONE, REAL, CONCEAL }

/** One queued voice packet, on either the arrival-order or the timed path. */
internal class JitterPacket(
    val frameNumber: Long,
    val pcm: ShortArray?,
    val opus: ByteArray?,
    val isLast: Boolean,
    var spanSamples: Int,
    /**
     * Audio context of this packet (normal / shout / whisper / listener).
     * Server→client metadata, so it is decided per packet at receive time and
     * carried to playback, where the talk state is derived from it.
     */
    val context: AudioContext = AudioContext.NORMAL,
) {
    val spanFrames: Int
        get() = OpusCodec.tenMsFrames(spanSamples).coerceAtLeast(1)
}

/**
 * The per-speaker playback state of [VoiceJitterBuffer]: the packet queues,
 * the play head, and the "is this speaker talking" bookkeeping. Mutable by
 * design — the buffer mutates it under its own lock.
 */
internal class JitterSession(initialPreroll: Int) {
    val queued = ArrayDeque<ShortArray>()
    val timed = TreeMap<Long, JitterPacket>()
    var leftover: ShortArray? = null
    var leftoverPos = 0
    var started = false
    var lastPushMs = 0L
    var missCount = 0
    var talking = false

    /** Context of the last real (non-concealed) packet pulled. */
    var context = AudioContext.NORMAL

    /** Last talk state reported through `onTalking` for this session. */
    var reportedState = TalkState.PASSIVE
    var timedMode = false
    var playHead: Long? = null
    var targetPreroll = initialPreroll
    var lastDecodedSamples = OpusCodec.FRAME_SIZE_10MS * 2
    var needFadeIn = true
    var ending = false
    var leftoverConceal = false

    /** Extra 10 ms PLC holds to grow delay in the current utterance. */
    var delayBoostRemaining = 0

    /** Samples still unread in [leftover]. */
    fun leftoverRemaining(): Int {
        val left = leftover ?: return 0
        return (left.size - leftoverPos).coerceAtLeast(0)
    }

    /** True when nothing is queued and no partial frame is left to play. */
    fun isDrained(): Boolean = queued.isEmpty() && timed.isEmpty() && leftoverRemaining() == 0
}
