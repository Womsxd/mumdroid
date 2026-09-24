package dev.woms.mumdroid.core.audio

import android.media.AudioDeviceInfo
import android.util.Log
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VadMethod
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures microphone audio at 48 kHz mono 16-bit and delivers fixed-size
 * frames to a sink, running them through the shared [MicCaptureEngine]
 * (platform effects -> software AEC -> denoiser/AGC -> VAD -> manual gain).
 *
 * All capture configuration is delegated to the engine; see
 * [MicCaptureEngine.applySettings].
 */
class AudioInput {

    companion object {
        private const val TAG = "AudioInput"

        /** How long [stop] waits for the capture thread before releasing the mic. */
        private const val STOP_JOIN_MS = 500L
    }

    interface Sink {
        /** Called on a background thread with one audio frame of PCM. */
        fun onPcmFrame(pcm: ShortArray)

        /**
         * Called on a background thread when the voice-activity detector
         * decides whether the current frame contains speech. Only invoked when
         * VAD gating is enabled (voice-activated mode).
         */
        fun onSpeechDetected(active: Boolean) {}

        /**
         * Called on a background thread with the current normalised voice level
         * (0..100) as reported by the VAD detector, so the UI can render a live
         * level meter. Only invoked when VAD is active.
         */
        fun onVadLevel(level: Int) {}
    }

    private val engine = MicCaptureEngine()
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var thread: Thread? = null
    private var sink: Sink? = null

    /**
     * Applies a configuration snapshot to the shared capture engine.
     *
     * Parameters left out keep the engine's current value, so a caller that
     * only cares about a subset (the noise / AGC stage, say) cannot silently
     * reset the rest. That matters most for [framesPerPacket], which also
     * determines the capture frame size: changing it on a live engine desyncs
     * it from the capture loop's buffer and from the framing the bandwidth
     * controller reports, so it must only move together with a capture restart.
     */
    fun applySettings(
        noiseEnabled: Boolean,
        mode: NoiseSuppressionMode,
        suppressionDb: Int,
        agcMode: AgcMode,
        agcEnabled: Boolean = engine.agcEnabled,
        agcMaxGainDb: Int = engine.agcMaxGainDb,
        inputVolume: Int = engine.inputVolume,
        aecMode: AecMode = engine.aecMode,
        aecEnabled: Boolean = engine.aecEnabled,
        micSource: MicSource = engine.micSource,
        vadGating: Boolean = engine.vadGating,
        vadMethod: VadMethod = engine.vadMethod,
        vadSpeechThreshold: Int = engine.vadSpeechThreshold,
        vadSilenceThreshold: Int = engine.vadSilenceThreshold,
        vadHoldFrames: Int = engine.vadHoldFrames,
        framesPerPacket: Int = engine.framesPerPacket,
    ) {
        engine.applySettings(
            micSource = micSource,
            noiseEnabled = noiseEnabled,
            mode = mode,
            suppressionDb = suppressionDb,
            agcMode = agcMode,
            agcEnabled = agcEnabled,
            agcMaxGainDb = agcMaxGainDb,
            inputVolume = inputVolume,
            aecMode = aecMode,
            aecEnabled = aecEnabled,
            vadGating = vadGating,
            vadMethod = vadMethod,
            vadSpeechThreshold = vadSpeechThreshold,
            vadSilenceThreshold = vadSilenceThreshold,
            vadHoldFrames = vadHoldFrames,
            framesPerPacket = framesPerPacket,
        )
    }

    fun start(sink: Sink) {
        this.sink = sink
        if (!running.compareAndSet(false, true)) return
        try {
            if (!engine.open()) {
                running.set(false)
                return
            }
            // Bound the encode/send queue: if TCP send blocks, drop the oldest
            // pending frames instead of growing unbounded latency (which the
            // far side hears as choppy / "electrical" Opus artifacts).
            val sendQueue = LinkedBlockingQueue<Runnable>(8)
            executor = ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                sendQueue,
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )
            thread = Thread { captureLoop() }.apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio input", e)
            running.set(false)
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(engine.frameSize)
        while (running.get()) {
            val read = engine.read(buffer)
            if (read < 0) {
                // The recording is in an invalid state — stop the loop instead
                // of busy-spinning on native errors (-38 etc.).
                Log.e(TAG, "AudioRecord read failed: $read")
                running.set(false)
                break
            }
            if (read != engine.frameSize) continue
            // Work on the frame in-place (AEC / preprocessor / gain modify it).
            // IMPORTANT: the buffer handed to the executor must be a *copy*;
            // the original `buffer` is reused on the next loop iteration and
            // would otherwise be overwritten before the encoder consumes it
            // (classic capture-thread / encoder-thread race that manifests as
            // choppy / garbled then always-noisy audio).
            val speech = engine.processFrame(buffer)
            val deliver = buffer.copyOf()
            val voice = engine.lastVoice()
            val vadLevel = engine.getVadLevel()
            // One runnable per frame so a bounded send queue cannot drop the
            // VAD-off callback independently of the PCM (which would skip the
            // end-of-transmission marker).
            if (engine.vadGating) {
                executor?.execute {
                    sink?.onSpeechDetected(speech)
                    sink?.onVadLevel(vadLevel)
                    if (speech) sink?.onPcmFrame(deliver)
                }
            } else {
                executor?.execute {
                    sink?.onSpeechDetected(voice)
                    sink?.onPcmFrame(deliver)
                }
            }
        }
    }

    fun stop() {
        val capture = thread
        thread = null
        running.set(false)
        // Unblock a pending AudioRecord.read() so the capture loop can observe
        // running == false and exit; Thread.interrupt() does not interrupt that
        // native call.
        engine.stopRecording()
        // Join the capture thread *before* engine.close() tears down the
        // AudioRecord and the native DSP state: the thread may be inside
        // engine.processFrame() (software AEC / preprocessor native calls), and
        // freeing that state underneath it is a native use-after-free.
        if (capture != null && capture !== Thread.currentThread()) {
            capture.interrupt()
            try {
                capture.join(STOP_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        engine.close()
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Feeds the speaker reference for the software AEC: called by the owner
     * (service) from the playback path with every PCM frame written to the
     * speaker.
     */
    fun pushFarEndFrame(pcm: ShortArray) {
        engine.pushFarEndFrame(pcm)
    }

    /** Pins capture to a headset / builtin mic; see [MicCaptureEngine.setPreferredDevice]. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        engine.setPreferredDevice(device)
    }
}
