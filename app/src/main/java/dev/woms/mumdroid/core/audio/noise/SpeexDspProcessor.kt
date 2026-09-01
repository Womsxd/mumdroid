package dev.woms.mumdroid.core.audio.noise

import android.util.Log

/**
 * JNI binding to the native speexdsp preprocessor (xiph/speexdsp, vendored as
 * a git submodule and built as `libspeexdsp.so` via CMake from
 * `app/src/main/cpp/`).
 *
 * This is the same noise-suppression backend used by the desktop Mumble
 * client. mumdroid uses it for denoising only; AGC and VAD remain in the
 * Kotlin pipeline so behaviour is identical across all suppression modes.
 *
 * Instances are single-threaded and must be used from one audio thread at a
 * time. Always call [close] to release the native state.
 */
class SpeexDspProcessor(private val frameSize: Int, private val sampleRate: Int) {

    companion object {
        private const val TAG = "SpeexDspProcessor"

        private val loaded = runCatching { System.loadLibrary("speexdsp") }.isSuccess

        /** Whether the native speexdsp backend is available on this device. */
        val isAvailable: Boolean get() = loaded
    }

    /** Native pointer (an opaque `SpeexPreprocessState*`); 0 once closed. */
    @Volatile
    private var nativeHandle: Long = 0

    val isInitialized: Boolean
        get() = nativeHandle != 0L

    init {
        if (loaded) {
            nativeHandle = nativeCreate(frameSize, sampleRate)
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to create native speexdsp preprocessor state")
            }
        }
    }

    /**
     * Denoises one frame of 16-bit PCM in place.
     *
     * On any native failure the buffer is left untouched (passthrough) so a
     * broken backend can never turn the microphone into digital silence.
     *
     * @return whether speech was detected by the internal VAD.
     */
    fun run(samples: ShortArray): Boolean {
        if (nativeHandle == 0L || samples.isEmpty()) return false
        return nativeRun(nativeHandle, samples) == 1
    }

    fun setDenoise(enable: Boolean) {
        if (nativeHandle != 0L) nativeSetDenoise(nativeHandle, enable)
    }

    /** The noise suppression level in dB (negative value, e.g. -15). */
    fun setNoiseSuppress(db: Int) {
        if (nativeHandle != 0L) nativeSetNoiseSuppress(nativeHandle, db)
    }

    /**
     * Enables/disables the speexdsp built-in AGC (the desktop client's gain
     * backend). Recommended to pair with denoise, which feeds it clean noise
     * estimates.
     */
    fun setAgc(enable: Boolean) {
        if (nativeHandle != 0L) nativeSetAgc(nativeHandle, enable)
    }

    /** The AGC target level (1..32768; desktop uses 30000). */
    fun setAgcTarget(target: Int) {
        if (nativeHandle != 0L) nativeSetAgcTarget(nativeHandle, target)
    }

    /** Maximal AGC gain in dB (desktop derives ~30 dB from iMinLoudness). */
    fun setAgcMaxGain(db: Int) {
        if (nativeHandle != 0L) nativeSetAgcMaxGain(nativeHandle, db)
    }

    /** Maximal AGC gain increase in dB/second (desktop: 12). */
    fun setAgcIncrement(dbPerSec: Int) {
        if (nativeHandle != 0L) nativeSetAgcIncrement(nativeHandle, dbPerSec)
    }

    /** Maximal AGC gain decrease in dB/second (desktop: 60). */
    fun setAgcDecrement(dbPerSec: Int) {
        if (nativeHandle != 0L) nativeSetAgcDecrement(nativeHandle, dbPerSec)
    }

    /**
     * Associates an echo canceller state so the preprocessor can use its
     * residual-echo suppression. Pass the raw handle of a
     * [SpeexEchoCanceller], or 0 to detach.
     */
    fun setEchoState(echoHandle: Long) {
        if (nativeHandle != 0L) nativeSetEchoState(nativeHandle, echoHandle)
    }

    /** Releases the native state. Safe to call multiple times. */
    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // --- native methods (JNI, see app/src/main/cpp/speexdsp_jni.c) ---

    private external fun nativeCreate(frameSize: Int, sampleRate: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetDenoise(handle: Long, enable: Boolean)
    private external fun nativeSetNoiseSuppress(handle: Long, db: Int)
    private external fun nativeSetAgc(handle: Long, enable: Boolean)
    private external fun nativeSetAgcTarget(handle: Long, target: Int)
    private external fun nativeSetAgcMaxGain(handle: Long, db: Int)
    private external fun nativeSetAgcIncrement(handle: Long, dbPerSec: Int)
    private external fun nativeSetAgcDecrement(handle: Long, dbPerSec: Int)
    private external fun nativeSetEchoState(handle: Long, echoHandle: Long)
    private external fun nativeRun(handle: Long, samples: ShortArray): Int
}
