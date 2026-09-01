package dev.woms.mumdroid.core.audio

import android.util.Log
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A lightweight microphone level meter used by the settings audio page.
 *
 * It drives the same shared [MicCaptureEngine] as the live capture pipeline
 * ([AudioInput]), so the reported level always reflects the audio that would
 * actually be transmitted: platform effects (system AGC/AEC/NS), software AEC,
 * denoising, native AGC and the manual input gain.
 *
 * The microphone must be granted `RECORD_AUDIO` before [start].
 *
 * Call [start] to begin capturing and [stop] to release the microphone. Levels
 * are delivered on a background thread via the callback passed to the
 * constructor.
 */
class MicLevelMeter(
    private val onLevel: (Int) -> Unit,
    noiseSuppressionEnabled: Boolean = false,
    noiseSuppressionMode: NoiseSuppressionMode = NoiseSuppressionMode.SPEEX,
    noiseSuppressionDb: Int = 15,
    agcMode: AgcMode = AgcMode.SPEEX,
    agcEnabled: Boolean = true,
    agcMaxGainDb: Int = 30,
    aecMode: AecMode = AecMode.SYSTEM,
    aecEnabled: Boolean = false,
    micSource: MicSource = MicSource.MIC,
    inputVolume: Int = 100,
) {
    companion object {
        private const val TAG = "MicLevelMeter"
        private const val SAMPLE_RATE = 48000

        /** Read small frames so the meter stays responsive (20 ms @ 48 kHz). */
        private const val FRAME_SAMPLES = 960

        /** Maps RMS to a normalised 0..100 level (see [rmsToLevel]). */
        private const val DB_RANGE = 60.0
    }

    // The engine applies platform effects, denoise, AGC and manual gain — the
    // exact chain of the real capture pipeline.
    private val engine = MicCaptureEngine(SAMPLE_RATE).apply {
        applySettings(
            micSource = micSource,
            noiseEnabled = noiseSuppressionEnabled,
            mode = noiseSuppressionMode,
            suppressionDb = noiseSuppressionDb,
            agcMode = agcMode,
            agcEnabled = agcEnabled,
            agcMaxGainDb = agcMaxGainDb,
            inputVolume = inputVolume,
            aecMode = aecMode,
            aecEnabled = aecEnabled,
            framesPerPacket = FRAME_SAMPLES / OpusCodec.FRAME_SIZE_10MS,
        )
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Whether the meter is currently capturing microphone audio. */
    val isRunning: Boolean
        get() = running

    /**
     * Starts reading microphone audio, applying the configured processing and
     * reporting the resulting level. No-op if already running. Silently stops
     * if the mic cannot be opened (e.g. no permission).
     */
    fun start() {
        if (running) return
        running = true
        try {
            if (!engine.open()) {
                running = false
                return
            }
            thread = Thread({ captureLoop() }, "mic-level").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic level capture", e)
            running = false
        }
    }

    /** Stops capturing and releases the microphone resources. */
    fun stop() {
        running = false
        engine.close()
        try {
            thread?.join(200)
        } catch (_: Exception) {
        }
        thread = null
    }

    private fun captureLoop() {
        val buf = ShortArray(FRAME_SAMPLES)
        while (running) {
            val read = engine.read(buf)
            if (read < 0) {
                // Invalid recording state — stop instead of busy-spinning.
                Log.e(TAG, "AudioRecord read failed: $read")
                running = false
                break
            }
            if (read == FRAME_SAMPLES) {
                // Mirror the real transmission chain (platform effects ->
                // AEC -> denoise -> AGC -> manual gain) before computing level.
                engine.processFrame(buf)
                onLevel(rmsToLevel(buf, FRAME_SAMPLES))
            }
        }
    }

    /**
     * Converts the RMS amplitude of the read samples to a normalised 0..100
     * level. Full scale (32767) maps to 100; levels below -60 dBFS are shown as
     * 0, matching the range the desktop client exposes on its VAD meter.
     */
    private fun rmsToLevel(buf: ShortArray, count: Int): Int {
        var sumSq = 0.0
        for (i in 0 until count) {
            val v = buf[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / count)
        // dB relative to full scale (clamp rms to 1 to avoid log(0)).
        val db = 20.0 * ln(max(rms, 1.0) / 32768.0) / ln(10.0)
        val level = ((db + DB_RANGE) / DB_RANGE * 100.0).toInt()
        return level.coerceIn(0, 100)
    }
}
