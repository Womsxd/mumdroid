package dev.woms.mumdroid.core.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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

    private val opus = OpusCodec()
    private val jitter = VoiceJitterBuffer(maxQueuedFrames = 16).apply {
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
     * playback thread with every PCM frame right before it is written to the
     * speaker, so the AEC sees exactly what is audible.
     */
    @Volatile
    var echoReferenceTap: ((ShortArray) -> Unit)? = null

    /** Fired when a speaker's jitter buffer goes idle (end of talk spurt). */
    @Volatile
    var speakerIdleTap: ((Int) -> Unit)? = null

    /** Playback-liveness talk flag, from the mix (PC AudioOutputSpeech). */
    @Volatile
    var speakerTalkingTap: ((Int, Boolean) -> Unit)? = null

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
        while (running.get()) {
            jitter.mix(mix)
            val playback = applyOutputGain(mix)
            echoReferenceTap?.invoke(playback)
            val written = try {
                t.write(playback, 0, playback.size)
            } catch (_: IllegalStateException) {
                // Track was released (or never fully started) while we were
                // inside the blocking native write; just leave the loop.
                return
            }
            if (written < 0) {
                if (!running.get()) return
                Log.e(TAG, "AudioTrack write failed: $written")
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Applies the [volume] gain (0..200 %) to the frame before playback. */
    private fun applyOutputGain(frame: ShortArray): ShortArray {
        val v = volume
        if (v == 100) return frame
        val gain = v / 100.0
        val out = ShortArray(frame.size)
        for (i in frame.indices) {
            out[i] = SoftLimiter.limit(frame[i] * gain).roundToInt().toShort()
        }
        return out
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
    fun writePacket(session: Int, frameNumber: Long, payload: ByteArray, isLast: Boolean = false) {
        if (!running.get()) return
        jitter.pushEncoded(session, frameNumber, payload, isLast)
    }

    /** Compatibility: treat untagged PCM as session 0. */
    fun write(pcm: ShortArray) = write(0, pcm)

    fun stop() {
        synchronized(lock) {
            running.set(false)
            val playback = thread
            thread = null
            // Join before release. write() is a blocking native call and is
            // not interrupted by Thread.interrupt(); tearing the track down
            // while it is inside native_write_short throws IllegalStateException
            // and takes down the process (seen on connect-then-immediate-disconnect).
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
