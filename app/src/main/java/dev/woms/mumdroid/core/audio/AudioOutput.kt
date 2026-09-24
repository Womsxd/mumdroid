package dev.woms.mumdroid.core.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.model.TalkState
import kotlin.math.roundToInt

/**
 * Plays decoded 48 kHz mono 16-bit PCM through the device output.
 *
 * Each speaker is jitter-buffered independently and mixed into a fixed
 * 20 ms quantum. [AudioTrack.write] is the clock: when nobody is talking
 * silence is written so the track never underruns into clicks, and the
 * software AEC far-end tap stays time-aligned.
 */
class AudioOutput(
    private val mediaUsage: Boolean = false,
    opusImplementation: OpusImplementation = OpusImplementation.LIBOPUS,
) {

    companion object {
        private const val TAG = "AudioOutput"
        private const val SAMPLE_RATE = 48000
        /** 20 ms mix quantum (960 samples @ 48 kHz), matching typical Opus frames. */
        const val MIX_QUANTUM = 960
        /**
         * [AudioTrack.write] in MODE_STREAM blocks until the frame is queued.
         * Stop waits this long for that write to finish before releasing the
         * track; releasing while write() is in native code crashes with
         * "Unable to retrieve AudioTrack pointer for write()".
         */
        private const val STOP_JOIN_MS = 500L
    }

    private val opus = OpusCodec(opusImplementation)
    private val jitter = VoiceJitterBuffer(
        maxQueuedFrames = 16,
        clock = { SystemClock.elapsedRealtime() },
    ).apply {
        decoder = object : VoiceJitterBuffer.Decoder {
            override fun decode(session: Int, payload: ByteArray, isLast: Boolean): ShortArray? =
                opus.decodeForSession(session, payload, isLast)

            override fun conceal(session: Int, samples: Int): ShortArray? =
                opus.decodePlc(session, samples)

            override fun reset(session: Int) {
                opus.resetDecoder(session)
            }
        }
    }
    private val lock = Any()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** Volume of incoming speech (0..200 %, 100 = unity). */
    @Volatile
    var volume: Int = 100

    /**
     * Speaker reference tap for software echo cancellation: invoked from the
     * playback thread with the PCM that was actually queued to the speaker —
     * once per quantum normally, or slice by slice when a write only queues
     * part of it — so the AEC sees exactly what is audible.
     */
    @Volatile
    var echoReferenceTap: ((ShortArray) -> Unit)? = null

    /** Fired when a speaker's jitter buffer goes idle (end of talk spurt). */
    @Volatile
    var speakerIdleTap: ((Int) -> Unit)? = null

    /**
     * Playback-liveness talk state, from the mix (PC AudioOutputSpeech). The
     * state is [TalkState.PASSIVE] when the speaker goes quiet, otherwise it
     * reflects the received audio context (talking / whispering / shouting).
     */
    @Volatile
    var speakerTalkingTap: ((Int, TalkState) -> Unit)? = null

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        try {
            track?.setPreferredDevice(device)
        } catch (_: Exception) {
        }
    }

    fun start() {
        synchronized(lock) {
            if (!running.compareAndSet(false, true)) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val attributes = AudioAttributes.Builder()
                    .setUsage(
                        if (mediaUsage) AudioAttributes.USAGE_MEDIA
                        else AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
                val quantumBytes = MIX_QUANTUM * 2
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf, quantumBytes * 6))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                if (t.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed")
                    t.release()
                    running.set(false)
                    return
                }
                track = t
                jitter.onSessionEnded = { session -> speakerIdleTap?.invoke(session) }
                jitter.onTalking = { session, talking -> speakerTalkingTap?.invoke(session, talking) }
                t.setPreferredDevice(preferredDevice)
                t.play()
                thread = Thread({ playbackLoop(t) }, "audio-out").apply { start() }
            } catch (e: Exception) {
                running.set(false)
                releaseTrackLocked()
                Log.e(TAG, "Failed to start audio output", e)
            }
        }
    }

    private fun playbackLoop(t: AudioTrack) {
        val mix = ShortArray(MIX_QUANTUM)
        // Owned by this — the only — playback thread, exactly like `mix`: a
        // non-unity volume writes here instead of allocating a fresh PCM buffer
        // on every quantum.
        val gainScratch = ShortArray(MIX_QUANTUM)
        while (running.get()) {
            jitter.mix(mix)
            val playback = applyOutputGain(mix, gainScratch)
            val queued = try {
                writeQuantumToTrack(playback, echoReferenceTap) { offset, count ->
                    t.write(playback, offset, count)
                }
            } catch (_: IllegalStateException) {
                // Track was released (or never fully started) while we were
                // inside the blocking native write; just leave the loop.
                return
            }
            if (queued != playback.size) {
                // The track queued nothing. A negative AudioTrack.ERROR_* code
                // means it is unusable (ERROR_DEAD_OBJECT after the audio server
                // restarts, ERROR_INVALID_OPERATION on a released track) and a
                // zero cannot make progress, so retrying the same call is
                // pointless: the old code logged and slept 5 ms forever, spinning
                // at ~200 Hz, spamming the log and draining the jitter buffer
                // while the output stayed silent. Stop cleanly instead — the
                // object then sits in a defined stopped state and a later
                // start() can rebuild the track.
                if (running.compareAndSet(true, false)) {
                    Log.e(TAG, "AudioTrack write failed ($queued), stopping playback")
                }
                return
            }
        }
    }

    /**
     * Applies the [volume] gain (0..200 %) to the quantum before playback.
     *
     * At unity gain [frame] is returned as-is; otherwise the result is written
     * into [scratch] (which the caller owns and reuses, so the hot path
     * allocates nothing) and returned. [scratch] must be at least as long as
     * [frame].
     */
    private fun applyOutputGain(frame: ShortArray, scratch: ShortArray): ShortArray {
        val v = volume
        if (v == 100) return frame
        val gain = v / 100.0
        for (i in frame.indices) {
            scratch[i] = SoftLimiter.limit(frame[i] * gain).roundToInt().toShort()
        }
        return scratch
    }

    /**
     * Queues a decoded PCM frame from [session] into that speaker's jitter
     * buffer. Frames of different speakers are mixed on the playback thread.
     */
    fun write(session: Int, pcm: ShortArray) {
        if (!running.get()) return
        jitter.push(session, pcm)
    }

    /**
     * Queues an encoded Opus packet stamped with official `frameNumber`
     * (10 ms units). Decode and PLC happen on the playback thread in
     * timestamp order.
     */
    fun writePacket(
        session: Int,
        frameNumber: Long,
        payload: ByteArray,
        isLast: Boolean = false,
        context: AudioContext = AudioContext.NORMAL,
    ) {
        if (!running.get()) return
        jitter.pushEncoded(session, frameNumber, payload, isLast, context)
    }

    /** Switches the Opus decode backend; per-session decoder state is dropped. */
    fun setOpusImplementation(implementation: OpusImplementation) {
        opus.setImplementation(implementation)
    }

    /** Compatibility: treat untagged PCM as session 0. */
    fun write(pcm: ShortArray) = write(0, pcm)

    fun stop() {
        synchronized(lock) {
            running.set(false)
            val playback = thread
            thread = null
            // Stop the track *before* the join: a blocking write() only returns
            // once the track stops consuming data, so this is what unblocks the
            // playback thread and lets the join below succeed —
            // Thread.interrupt() cannot break that native call. Tearing the
            // track down while it is still inside native_write_short is what
            // aborts the process ("Unable to retrieve AudioTrack pointer for
            // write()", seen on connect-then-immediate-disconnect).
            stopTrackLocked()
            if (playback != null && playback !== Thread.currentThread()) {
                playback.interrupt()
                try {
                    playback.join(STOP_JOIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            releaseTrackLocked()
            jitter.clear()
            opus.close()
        }
    }

    /** Pauses and stops the track without releasing it, so a pending write can return. */
    private fun stopTrackLocked() {
        try {
            track?.pause()
            track?.stop()
        } catch (_: Exception) {
        }
    }

    private fun releaseTrackLocked() {
        val t = track
        track = null
        if (t == null) return
        try {
            t.pause()
            t.stop()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * Pushes one mixed quantum to the output track, tolerating a short write.
 *
 * A blocking [AudioTrack.write] normally queues the whole quantum, but it can
 * return a smaller sample count when the track is stopped or paused underneath
 * it. The remainder is then re-written from the new offset instead of being
 * dropped, and [tap] receives exactly the slice that was queued — so the
 * software AEC's far-end reference never reports more than is audible (on a
 * full write the slice *is* [playback], so the hot path stays allocation-free).
 *
 * Declared `inline` so the [write] lambda — which captures the track — does not
 * allocate a closure once per quantum. [tap] is `noinline` because a nullable
 * function type cannot be an inline parameter; it costs nothing, since callers
 * pass the already-allocated [AudioOutput.echoReferenceTap] value.
 *
 * @param write `write(offset, count)`, returning the number of samples it
 *        queued (> 0), 0 when it queued nothing, or a negative
 *        `AudioTrack.ERROR_*` code.
 * @return the number of samples queued: `playback.size` when the whole quantum
 *         went out, otherwise the non-positive value that ended the loop, which
 *         means the track is unusable and the caller must stop rather than
 *         retry the same call.
 * @throws IllegalStateException as [AudioTrack.write] does when the track has
 *         been released underneath the call.
 */
internal inline fun writeQuantumToTrack(
    playback: ShortArray,
    noinline tap: ((ShortArray) -> Unit)?,
    write: (offset: Int, count: Int) -> Int,
): Int {
    var offset = 0
    while (offset < playback.size) {
        val written = write(offset, playback.size - offset)
        if (written <= 0) return written
        tap?.invoke(
            if (offset == 0 && written == playback.size) {
                playback
            } else {
                playback.copyOfRange(offset, offset + written)
            },
        )
        offset += written
    }
    return offset
}
