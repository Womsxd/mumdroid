package dev.woms.mumdroid.core.audio.noise

import android.util.Log

/**
 * JNI binding to the speexdsp echo canceller (software AEC).
 *
 * The captured microphone signal ("near end") is cancelled against the
 * speaker reference ("far end") so what the phone itself plays back is
 * suppressed from the transmission.
 *
 * The far-end reference is produced by [pushFar] (called from the playback
 * path with whatever was just written to the speaker) and buffered internally;
 * [process] consumes exactly one frame of far-end audio per call, so capture
 * and playback frame sizes do not have to match. When the buffer runs dry
 * (playback silence) a zero reference is used, which makes the canceller a
 * passthrough for that frame.
 *
 * Instances are single-threaded on the capture side ([process]); [pushFar] is
 * called from the playback thread and is thread-safe. Always call [close].
 */
class SpeexEchoCanceller(
    private val frameSize: Int,
    private val sampleRate: Int,
    /** Echo tail length in ms (how long a reflection may arrive). */
    private val filterLengthMs: Int = 200,
) {
    companion object {
        private const val TAG = "SpeexEchoCanceller"

        /** Same shared library as the preprocessor binding. */
        val isAvailable: Boolean get() = SpeexDspProcessor.isAvailable

        private const val MAX_BUFFERED_FAR_SAMPLES = 48_000 // ~1 s @ 48 kHz
    }

    @Volatile
    private var nativeHandle: Long = 0

    private val lock = Any()

    // Far-end ring buffer. A primitive ShortArray avoids per-sample Short
    // boxing: the old ArrayDeque<Short> allocated ~48k objects per second
    // (one per sample of every 20ms playback frame) on the realtime playback
    // thread, and both realtime threads then fought over this lock while the
    // GC chewed through ~1 MB/s of garbage. All three fields are guarded by
    // [lock]; the single lock still keeps push/process ordering correct.
    private val farRing = ShortArray(MAX_BUFFERED_FAR_SAMPLES)
    private var farRead = 0
    private var farWrite = 0
    private var farAvailable = 0

    private val nearBuf = ShortArray(frameSize)
    private val farBuf = ShortArray(frameSize)
    private val outBuf = ShortArray(frameSize)

    val isInitialized: Boolean
        get() = nativeHandle != 0L

    /** Raw handle for associating this canceller with a preprocessor state. */
    fun nativePtr(): Long = nativeHandle

    fun start(): Boolean {
        if (!isAvailable) return false
        if (nativeHandle != 0L) return true
        nativeHandle = nativeCreate(frameSize, sampleRate * filterLengthMs / 1000)
        if (nativeHandle == 0L) {
            Log.e(TAG, "Failed to create native echo canceller state")
        }
        return nativeHandle != 0L
    }

    /**
     * Feeds the speaker reference: called from the playback path with the
     * PCM that was just written to the speaker.
     */
    fun pushFar(pcm: ShortArray) {
        synchronized(lock) {
            for (v in pcm) {
                // Overflow drops the oldest sample (the read pointer trails
                // the write pointer), exactly what the old queue's
                // removeFirst() did — latency stays bounded.
                if (farAvailable == farRing.size) {
                    farRead = (farRead + 1) % farRing.size
                } else {
                    farAvailable++
                }
                farRing[farWrite] = v
                farWrite = (farWrite + 1) % farRing.size
            }
        }
    }

    /**
     * Cancels one captured microphone frame in place against the buffered
     * far-end reference. On any failure the frame is left untouched
     * (passthrough), so a broken AEC can never mute the mic.
     *
     * @param frame the captured near-end frame; modified in place.
     * @return whether cancellation ran (false = passthrough / starved state).
     */
    fun process(frame: ShortArray): Boolean {
        if (nativeHandle == 0L || frame.size != frameSize) return false

        synchronized(lock) {
            for (i in 0 until frameSize) {
                farBuf[i] = if (farAvailable > 0) {
                    val v = farRing[farRead]
                    farRead = (farRead + 1) % farRing.size
                    farAvailable--
                    v
                } else {
                    0
                }
            }
        }
        System.arraycopy(frame, 0, nearBuf, 0, frameSize)

        val ok = nativeCancel(nativeHandle, nearBuf, farBuf, outBuf)
        if (ok) {
            System.arraycopy(outBuf, 0, frame, 0, frameSize)
        }
        return ok
    }

    /** Releases the native state. Safe to call multiple times. */
    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        synchronized(lock) {
            farRead = 0
            farWrite = 0
            farAvailable = 0
        }
    }

    // --- native methods (JNI, see app/src/main/cpp/speexdsp_jni.c) ---

    private external fun nativeCreate(frameSize: Int, filterLength: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeCancel(handle: Long, near: ShortArray, far: ShortArray, out: ShortArray): Boolean
}
