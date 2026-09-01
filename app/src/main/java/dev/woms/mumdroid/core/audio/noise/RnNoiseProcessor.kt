package dev.woms.mumdroid.core.audio.noise

import android.util.Log

/**
 * JNI binding to the native RNNoise noise-suppression library.
 *
 * RNNoise (xiph/rnnoise) is a real-time neural-network based full-band noise
 * suppressor — the same backend used by the desktop Mumble client. It is built
 * as a native shared library (`librnnoise.so`) via CMake from
 * `app/src/main/cpp/`.
 *
 * The native processing frame is 480 samples (10 ms at 48 kHz). This wrapper
 * exposes a flexible interface that accepts any positive multiple of 480 (so
 * 10/20/40/60 ms Opus frames all work); the native side splits each frame into
 * consecutive 10 ms sub-frames automatically.
 *
 * Instances are single-threaded and must be used from one audio thread at a
 * time. Always call [close] to release the native state.
 */
class RnNoiseProcessor {

    companion object {
        private const val TAG = "RnNoiseProcessor"

        /** The native RNNoise frame size in samples (480 = 10 ms @ 48 kHz). */
        const val FRAME_SIZE = 480

        private val loaded = runCatching { System.loadLibrary("rnnoise") }.isSuccess

        /**
         * The native RNNoise frame size in samples (480 @ 48 kHz).
         * Queried lazily via a throw-away instance so tests can assert the
         * frame layout without constructing a persistent state.
         */
        val nativeFrameSize: Int by lazy {
            if (!loaded) 0 else RnNoiseProcessor().run {
                val size = nativeGetFrameSize()
                close()
                size
            }
        }
    }

    /** Native pointer (an opaque `RnNoiseHandle*`); 0 once closed. */
    @Suppress("unused")
    private var nativeHandle: Long = 0

    val isInitialized: Boolean
        get() = nativeHandle != 0L

    init {
        if (loaded) {
            nativeHandle = nativeCreate()
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to create native RNNoise state")
            }
        }
    }

    /**
     * Denoises one frame of 16-bit PCM in place. The frame length must be a
     * positive multiple of [FRAME_SIZE] (480 = 10 ms at 48 kHz), so 20 ms
     * (960), 40 ms (1920) and 60 ms (2880) packets are all supported and are
     * split into consecutive 10 ms sub-frames on the native side.
     *
     * On any native failure the buffer is left untouched (passthrough) so a
     * broken backend can never turn the microphone into digital silence.
     *
     * @param samples the frame to process; modified in place on return.
     * @return `true` if any sub-frame contains speech according to the internal VAD.
     */
    fun run(samples: ShortArray): Boolean {
        if (nativeHandle == 0L) return false
        if (samples.isEmpty() || samples.size % FRAME_SIZE != 0) return false
        val out = ShortArray(samples.size)
        val speech = nativeProcess(samples, out)
        if (speech < 0) {
            // Native-side failure: keep the input samples (passthrough)
            // instead of overwriting them with an all-zero output buffer,
            // which used to mute the entire mic whenever the backend failed.
            return false
        }
        System.arraycopy(out, 0, samples, 0, samples.size)
        return speech == 1
    }

    /** Releases the native state. Safe to call multiple times. */
    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy()
            nativeHandle = 0L
        }
    }

    // --- native methods (JNI, see app/src/main/cpp/rnnoise_jni.c) ---

    private external fun nativeGetFrameSize(): Int
    private external fun nativeCreate(): Long
    private external fun nativeProcess(input: ShortArray, output: ShortArray): Int
    private external fun nativeDestroy()
}
