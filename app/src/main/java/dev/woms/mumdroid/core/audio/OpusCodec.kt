package dev.woms.mumdroid.core.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import android.util.Log

/**
 * A thin wrapper around the Concentus Opus codec (a pure-Java port of libopus)
 * provided by the `io.github.jaredmdobson:concentus` Maven artifact. No native
 * libraries are required, which keeps the APK self-contained and buildable in
 * any Android CI environment.
 *
 * Mumble transmits a single mono channel of 48 kHz audio encoded with Opus.
 * This class exposes encode/decode operations used by the audio input and
 * output pipelines.
 */
class OpusCodec {

    companion object {
        private const val TAG = "OpusCodec"
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1
        const val MAX_PACKET = 4000

        /** The fixed 10 ms Opus frame in samples at 48 kHz (480 samples). */
        const val FRAME_SIZE_10MS = 480

        /**
         * Number of 10 ms frames covered by [samples] at 48 kHz. Official
         * `iFrameCounter` / `audioData.frameNumber` use this unit.
         */
        fun tenMsFrames(samples: Int): Int =
            (samples / FRAME_SIZE_10MS).coerceAtLeast(if (samples > 0) 1 else 0)

        /**
         * Rounds [samples] down to a legal Opus frame size at 48 kHz.
         * @return 0 when shorter than the 2.5 ms minimum (120 samples).
         */
        fun snapSupportedFrameSize(samples: Int): Int = when {
            samples >= 2880 -> 2880
            samples >= 1920 -> 1920
            samples >= 960 -> 960
            samples >= 480 -> 480
            samples >= 240 -> 240
            samples >= 120 -> 120
            else -> 0
        }

        /**
         * Duration of an Opus packet in samples at 48 kHz, from the RFC 6716
         * TOC byte. Used so the jitter buffer can stamp [span] before decode.
         */
        fun packetSampleCount(packet: ByteArray): Int {
            if (packet.isEmpty()) return FRAME_SIZE_10MS * 2
            val toc = packet[0].toInt() and 0xff
            val config = toc ushr 3
            val code = toc and 0x03
            val frameMs = when (config) {
                in 0..11 -> when (config % 4) {
                    0 -> 10.0
                    1 -> 20.0
                    2 -> 40.0
                    else -> 60.0
                }
                in 12..15 -> if ((config and 1) == 0) 10.0 else 20.0
                else -> when (config and 3) {
                    0 -> 2.5
                    1 -> 5.0
                    2 -> 10.0
                    else -> 20.0
                }
            }
            val frames = when (code) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    if (packet.size < 2) 1
                    else (packet[1].toInt() and 0x3f).coerceIn(1, 48)
                }
            }
            return (frameMs * 48.0 * frames).toInt().coerceIn(120, 2880)
        }

        /** Decoders idle for this long are reaped to bound memory. */
        private const val DECODER_TTL_MS = 30_000L
        private const val MAX_DECODERS = 32
    }

    private var encoder: OpusEncoder
    // Shared decoder kept for backwards-compat single-stream callers (tests).
    private val decoder: OpusDecoder
    private val outBuffer = ByteArray(MAX_PACKET)
    private val pcmBuffer = ShortArray(MAX_PACKET)

    // Per-session decoders — Opus state is per-speaker. A single shared decoder
    // mixes CELT states of different speakers and produces persistent garbled
    // noise once two streams interleave, which matches "starts fine, then
    // always noisy".
    private val decoders = java.util.concurrent.ConcurrentHashMap<Int, OpusDecoder>()
    private val decoderLastUse = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** The current encode frame size in samples (default 20 ms = 960). */
    private var frameSize: Int = FRAME_SIZE_10MS * 2

    /** User "Low latency mode" flag (`bAllowLowDelay`). Actual application
     *  also requires bitrate ≥ 64 kbit/s, matching official AudioInput. */
    private var allowLowDelay = false

    private var currentApplication = OpusApplication.OPUS_APPLICATION_VOIP

    /** The currently applied encoding bitrate in bits-per-second (0 = default). */
    private var currentBitrate = 0

    private val encodeLock = Any()

    init {
        try {
            currentApplication = desiredApplication()
            encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, currentApplication)
            // OPUS_SET_VBR(0) — direct call to Concentus' public setter:
            // compile-time locked and R8-safe (the former reflective lookup
            // silently failed in release and only worked by CBR-default
            // coincidence).
            encoder.setUseVBR(false)
            decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        } catch (e: OpusException) {
            throw IllegalStateException("Failed to initialise Opus codec", e)
        }
    }

    /**
     * Sets the encode/decode frame size in samples. Valid Opus frame sizes at
     * 48 kHz are 120/240/480/960/1920/2880 samples (2.5/5/10/20/40/60 ms). The
     * frame size is rounded down to the nearest supported value.
     */
    fun setFrameSize(samples: Int) {
        val clamped = snapSupportedFrameSize(samples).let { if (it <= 0) 120 else it }
        synchronized(encodeLock) {
            frameSize = clamped
        }
    }

    /** The current frame size in samples. */
    fun getFrameSize(): Int = frameSize

    /**
     * Enables or disables the low-latency Opus application. When enabled the
     * encoder is re-created with [OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY]
     * (mirroring the desktop client's handling of the "Low latency mode"
     * checkbox for high bitrates), otherwise the standard speech/audio
     * application is used.
     */
    fun setLowLatency(enabled: Boolean) {
        synchronized(encodeLock) {
            if (allowLowDelay == enabled) return
            allowLowDelay = enabled
            try {
                reinitEncoder()
            } catch (e: OpusException) {
                Log.w(TAG, "Failed to re-initialise Opus encoder for low latency", e)
            }
        }
    }

    /**
     * Official AudioInput encoder application:
     * low-delay ≥ 64 kbit/s → RESTRICTED_LOWDELAY;
     * otherwise ≥ 32 kbit/s → AUDIO; else VOIP.
     */
    private fun desiredApplication(): OpusApplication = when {
        allowLowDelay && currentBitrate >= 64_000 ->
            OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY
        currentBitrate >= 32_000 || currentBitrate == 0 ->
            OpusApplication.OPUS_APPLICATION_AUDIO
        else -> OpusApplication.OPUS_APPLICATION_VOIP
    }

    private fun reinitEncoder() {
        val app = desiredApplication()
        val newEncoder = OpusEncoder(SAMPLE_RATE, CHANNELS, app)
        if (currentBitrate > 0) newEncoder.setBitrate(currentBitrate)
        newEncoder.setUseVBR(false)
        encoder = newEncoder
        currentApplication = app
    }

    /**
     * Official `OPUS_RESET_STATE` at the start of a talk spurt
     * (`bIsSpeech && !bPreviousVoice` → `bResetEncoder`).
     */
    fun resetEncoder() {
        synchronized(encodeLock) {
            try {
                encoder.resetState()
            } catch (_: Exception) {
                try {
                    reinitEncoder()
                } catch (e: OpusException) {
                    Log.w(TAG, "Failed to reset Opus encoder", e)
                }
            }
        }
    }

    /** Adjusts the encoding bitrate in bits-per-second. */
    fun setBitrate(bps: Int) {
        synchronized(encodeLock) {
            currentBitrate = bps
            try {
                if (desiredApplication() != currentApplication) reinitEncoder()
                encoder.setBitrate(bps)
                encoder.setUseVBR(false)
            } catch (e: OpusException) {
                Log.w(TAG, "Failed to set bitrate", e)
            }
        }
    }

    /**
     * Encodes a block of 16-bit PCM samples into an Opus packet.
     * @return the encoded packet bytes, or null on failure.
     */
    fun encode(pcm: ShortArray): ByteArray? {
        synchronized(encodeLock) {
            // Official opus_encode uses the actual PCM length, not a stale
            // configured size — a mismatch reads past the array and the far
            // side hears electrical / garbled speech.
            val samples = snapSupportedFrameSize(pcm.size)
            if (samples <= 0) return null
            return try {
                val len = encoder.encode(
                    pcm, 0, samples, outBuffer, 0, outBuffer.size,
                )
                if (len <= 0) null else outBuffer.copyOf(len)
            } catch (e: OpusException) {
                Log.w(TAG, "Opus encode failed", e)
                null
            }
        }
    }

    /**
     * Decodes an Opus packet into 16-bit PCM samples (single shared decoder).
     * Prefer [decodeForSession] for multi-speaker streams.
     * @return the decoded PCM samples, or null on failure.
     */
    fun decode(packet: ByteArray): ShortArray? = decodeForSession(0, packet, isTerminator = false)

    /**
     * Decodes an Opus packet for a specific speaker session. Each session gets
     * its own decoder state; mixing streams into one decoder produces the
     * "robotic/garbled then always noisy" symptom reported by users.
     *
     * @param session the speaker's session id (server->client)
     * @param packet the raw Opus payload
     * @param isTerminator whether this is the last frame of the utterance;
     *                     the decoder is reset afterwards to avoid state bleed.
     */
    fun decodeForSession(session: Int, packet: ByteArray, isTerminator: Boolean = false): ShortArray? {
        val dec = getOrCreateDecoder(session)
        // Allocate per-call buffer to avoid sharing pcmBuffer across threads
        // (UDP thread vs. TCP-tunnel thread can decode concurrently).
        val out = ShortArray(MAX_PACKET)
        return try {
            val len = synchronized(dec) {
                dec.decode(packet, 0, packet.size, out, 0, out.size, false)
            }
            if (isTerminator) {
                synchronized(dec) { try { dec.resetState() } catch (_: Exception) {} }
            }
            if (len <= 0) null else out.copyOf(len)
        } catch (e: OpusException) {
            Log.w(TAG, "Opus decode failed session=$session", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Opus decode failed session=$session", e)
            null
        }
    }

    /**
     * Packet-loss concealment: synthesizes replacement PCM for a lost frame
     * of [frameSize] samples. Concentus supports `data == null` for PLC
     * (see OpusDecoder javadoc: "This may be NULL if that previous packet was
     * lost in transit").
     */
    fun decodePlc(session: Int, frameSize: Int): ShortArray? {
        val dec = getOrCreateDecoder(session)
        val out = ShortArray(frameSize.coerceAtMost(MAX_PACKET))
        return try {
            val len = synchronized(dec) {
                // null data triggers PLC; len=0 signals PLC in Concentus
                try {
                    dec.decode(null, 0, 0, out, 0, out.size, false)
                } catch (_: Exception) {
                    // Fallback: some builds reject null — just generate silence
                    -1
                }
            }
            if (len <= 0) null else out.copyOf(len)
        } catch (e: Exception) {
            null
        }
    }

    /** Resets (or drops) the decoder for [session], e.g. on terminator. */
    fun resetDecoder(session: Int) {
        decoders[session]?.let { dec ->
            synchronized(dec) { try { dec.resetState() } catch (_: Exception) {} }
        }
    }

    /** Evicts decoders idle longer than [DECODER_TTL_MS] or when over capacity. */
    private fun reapIdleDecoders() {
        if (decoders.size <= MAX_DECODERS) {
            val now = System.currentTimeMillis()
            val it = decoderLastUse.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > DECODER_TTL_MS) {
                    decoders.remove(e.key)
                    it.remove()
                }
            }
        } else {
            // Over capacity: drop oldest half
            val sorted = decoderLastUse.entries.sortedBy { it.value }
            for (i in 0 until sorted.size / 2) {
                decoders.remove(sorted[i].key)
                decoderLastUse.remove(sorted[i].key)
            }
        }
    }

    private fun getOrCreateDecoder(session: Int): OpusDecoder {
        decoderLastUse[session] = System.currentTimeMillis()
        // Periodic reap (cheap, ~once per 100 decodes)
        if ((decoderLastUse.size and 0x7F) == 0) reapIdleDecoders()
        return decoders.computeIfAbsent(session) {
            try {
                OpusDecoder(SAMPLE_RATE, CHANNELS)
            } catch (e: OpusException) {
                throw IllegalStateException("Failed to create Opus decoder for session $session", e)
            }
        }
    }

    fun close() {
        // Concentus has no explicit close; clear per-session map.
        decoders.clear()
        decoderLastUse.clear()
    }
}
