package dev.woms.mumdroid.core.audio

import android.util.Log
import dev.woms.mumdroid.core.audio.OpusCodec.Companion.tenMsFrames

/**
 * Opus encode/decode used by the audio input and output pipelines.
 *
 * Two backends are available:
 *  - [OpusImplementation.LIBOPUS]: native xiph/opus (git submodule v1.6.1)
 *    via JNI, compiled with the floating-point path. This is the default.
 *    Falls back to Concentus if `libopus.so` cannot be loaded.
 *  - [OpusImplementation.CONCENTUS]: the pure-Java Concentus port
 *    (`io.github.jaredmdobson:concentus`). Used on the JVM in unit tests and
 *    as a fallback.
 *
 * Mumble transmits a single mono channel of 48 kHz audio encoded with Opus.
 */
class OpusCodec(
    implementation: OpusImplementation = OpusImplementation.LIBOPUS,
) {

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
         * 10 ms units actually consumed by [encode]: [tenMsFrames] of the
         * snapped size. 30 ms / 50 ms PCM is not a legal Opus packet, so
         * using raw [tenMsFrames] would advance [VoiceFrameCounter] past
         * the audio that went on the wire.
         */
        fun encodedTenMsFrames(samples: Int): Int {
            val snapped = snapSupportedFrameSize(samples)
            if (snapped <= 0) return 0
            return tenMsFrames(snapped)
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

        internal fun createBackend(implementation: OpusImplementation): OpusBackend {
            if (implementation == OpusImplementation.LIBOPUS) {
                if (LibOpusNative.isAvailable) {
                    return try {
                        LibOpusBackend()
                    } catch (e: Exception) {
                        Log.w(TAG, "LibOpus init failed; falling back to Concentus", e)
                        ConcentusOpusBackend()
                    }
                }
                Log.w(TAG, "LibOpus native library unavailable; falling back to Concentus")
            }
            return ConcentusOpusBackend()
        }
    }

    @Volatile
    private var backend: OpusBackend = createBackend(implementation)

    /** The implementation last requested by the user (before fallback). */
    @Volatile
    var implementation: OpusImplementation = implementation
        private set

    /** The current encode frame size in samples (default 20 ms = 960). */
    private var frameSize: Int = FRAME_SIZE_10MS * 2

    /** User "Low latency mode" flag (`bAllowLowDelay`). Actual application
     *  also requires bitrate ≥ 64 kbit/s, matching official AudioInput. */
    private var allowLowDelay = false

    /** The currently applied encoding bitrate in bits-per-second (0 = default). */
    private var currentBitrate = 0

    private val encodeLock = Any()

    init {
        backend.ensureEncoder(desiredApplication(), currentBitrate)
    }

    /**
     * Switches the encode/decode backend. Encoder settings (bitrate, frame
     * size, low-latency) are re-applied; per-session decoder state is dropped.
     */
    fun setImplementation(implementation: OpusImplementation) {
        synchronized(encodeLock) {
            if (this.implementation == implementation) return
            val old = backend
            backend = createBackend(implementation)
            this.implementation = implementation
            backend.ensureEncoder(desiredApplication(), currentBitrate)
            old.close()
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
     * encoder is re-created with restricted-low-delay (mirroring the desktop
     * client's "Low latency mode" checkbox for high bitrates), otherwise the
     * standard speech/audio application is used.
     */
    fun setLowLatency(enabled: Boolean) {
        synchronized(encodeLock) {
            if (allowLowDelay == enabled) return
            allowLowDelay = enabled
            try {
                backend.ensureEncoder(desiredApplication(), currentBitrate)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-initialise Opus encoder for low latency", e)
            }
        }
    }

    /**
     * Official AudioInput encoder application:
     * low-delay ≥ 64 kbit/s → RESTRICTED_LOWDELAY;
     * otherwise ≥ 32 kbit/s → AUDIO; else VOIP.
     */
    private fun desiredApplication(): OpusApplicationMode = when {
        allowLowDelay && currentBitrate >= 64_000 -> OpusApplicationMode.LOW_DELAY
        currentBitrate >= 32_000 || currentBitrate == 0 -> OpusApplicationMode.AUDIO
        else -> OpusApplicationMode.VOIP
    }

    /**
     * Official `OPUS_RESET_STATE` at the start of a talk spurt
     * (`bIsSpeech && !bPreviousVoice` → `bResetEncoder`).
     */
    fun resetEncoder() {
        synchronized(encodeLock) {
            backend.resetEncoder()
        }
    }

    /** Adjusts the encoding bitrate in bits-per-second. */
    fun setBitrate(bps: Int) {
        synchronized(encodeLock) {
            currentBitrate = bps
            try {
                backend.ensureEncoder(desiredApplication(), bps)
            } catch (e: Exception) {
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
            return backend.encode(pcm, samples)
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
    fun decodeForSession(session: Int, packet: ByteArray, isTerminator: Boolean = false): ShortArray? =
        backend.decode(session, packet, isTerminator)

    /**
     * Packet-loss concealment: synthesizes replacement PCM for a lost frame
     * of [frameSize] samples. Both Concentus and libopus accept a null packet
     * for PLC.
     */
    fun decodePlc(session: Int, frameSize: Int): ShortArray? =
        backend.decodePlc(session, frameSize)

    /** Resets (or drops) the decoder for [session], e.g. on terminator. */
    fun resetDecoder(session: Int) {
        backend.resetDecoder(session)
    }

    fun close() {
        backend.close()
    }
}
