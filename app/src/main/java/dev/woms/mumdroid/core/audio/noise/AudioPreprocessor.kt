package dev.woms.mumdroid.core.audio.noise

import dev.woms.mumdroid.core.model.VadMethod
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Noise suppression engine selection, mirroring the desktop Mumble client
 * which offers several denoising presets:
 *
 *  - [SYSTEM]        the platform NoiseSuppressor effect on the mic session
 *  - [SPEEX]         the native speexdsp preprocessor (the desktop backend)
 *  - [RNNOISE]       native xiph RNNoise neural-network suppressor
 *  - [SPEEX_RNNOISE] both in series (RNNoise first, then speexdsp)
 */
enum class NoiseSuppressionMode {
    SYSTEM,
    SPEEX,
    RNNOISE,
    SPEEX_RNNOISE,
}

/**
 * A noise-suppression / audio pre-processor used by the audio input pipeline
 * before the samples are handed to the Opus encoder.
 *
 * It mirrors the interface of the official Mumble `AudioPreprocessor`
 * (see `src/mumble/AudioPreprocessor.{h,cpp}` in mumble-voip/mumble, BSD-3),
 * which wraps the Speex pre-processor. The desktop client lets the user pick
 * between Speex, RNNoise and Speex+RNNoise; this class provides the same
 * choice for the Android client, backed by the same native libraries
 * (xiph/speexdsp and xiph/rnnoise via JNI).
 *
 * Supported features (matching the official implementation):
 *  - Denoise (native speexdsp / RNNoise)
 *  - Voice activity detection (energy/spectral flatness based)
 *  - Automatic gain control (native speexdsp AGC via [setNativeAgc]; the
 *    platform AutomaticGainControl alternative is applied by AudioInput)
 *
 * The processing operates frame-wise on 16-bit mono PCM at 48 kHz (960 samples
 * per 20 ms frame, i.e. the Mumble Opus frame size).
 */
class AudioPreprocessor(
    /** The denoising engine to use. Defaults to the classic Speex-style one. */
    var mode: NoiseSuppressionMode = NoiseSuppressionMode.SPEEX,
) {

    companion object {
        private const val DEFAULT_NOISE_SUPPRESS_DB = -15.0
    }

    // --- configuration ---
    private var denoiseEnabled = true
    private var vadEnabled = false
    private var vadThreshold = 50
    private var vadMethod = VadMethod.AMPLITUDE
    private var vadSpeechThreshold = 98   // fVADmax (0..100)
    private var vadSilenceThreshold = 80  // fVADmin (0..100)
    private var vadHoldFrames = 20        // iVoiceHold (frames)
    private var dereverbEnabled = false
    private var noiseSuppressDb = DEFAULT_NOISE_SUPPRESS_DB

    // native RNNoise engine (only used when mode involves RNNOISE)
    private var rnnoise: RnNoiseProcessor? = null

    // native speexdsp preprocessor (used when mode involves Speex).
    private var speexDsp: SpeexDspProcessor? = null

    // --- dsp state ---
    private var initialized = false
    private var sampleRate = 0
    private var quantum = 0

    // Slowly-adapting scalar noise-floor RMS estimate used by the
    // signal-to-noise VAD method (fast attack down, very slow rise up).
    private var vadNoiseFloorRms = 1000.0

    // --- voice-activity detector state (desktop-style dual threshold + hold) ---
    // Whether the previous frame was considered speech (for hysteresis).
    private var vadPreviousVoice = false

    /** Latest VAD decision, updated every processed frame even when gating is off. */
    @Volatile
    var lastDetectedVoice: Boolean = false
        private set
    // Frames remaining in the voice-hold window after speech stops.
    private var vadHoldCounter = 0
    // The most recent normalised voice level (0..100) used by the UI bar.
    private var vadLevel = 0.0

    // --- lifecycle ---

    /** @return whether the object is initialized and can process audio. */
    val isInitialized: Boolean get() = initialized

    /** @return whether the processor is initialized for the given [quantum]. */
    fun matchesQuantum(quantum: Int): Boolean = initialized && this.quantum == quantum

    /**
     * Initializes the pre-processor. Must be called before any other method.
     *
     * @param sampleRate the input sample rate in Hz (e.g. 48000).
     * @param quantum    the number of samples per frame (e.g. 960 for 20 ms @ 48 kHz).
     */
    fun init(sampleRate: Int, quantum: Int): Boolean {
        this.sampleRate = sampleRate
        this.quantum = quantum

        vadNoiseFloorRms = 1000.0
        vadPreviousVoice = false
        vadHoldCounter = 0
        vadLevel = 0.0

        // (Re)create the native engines for the selected mode.
        if (mode == NoiseSuppressionMode.RNNOISE || mode == NoiseSuppressionMode.SPEEX_RNNOISE) {
            if (rnnoise == null) {
                rnnoise = RnNoiseProcessor()
            }
        } else {
            rnnoise?.close()
            rnnoise = null
        }
        speexDsp?.close()
        speexDsp = null
        if (quantum > 0 && sampleRate > 0 && SpeexDspProcessor.isAvailable) {
            val dsp = SpeexDspProcessor(quantum, sampleRate)
            dsp.setDenoise(true)
            dsp.setNoiseSuppress(noiseSuppressDb.roundToInt())
            speexDsp = dsp
        }

        initialized = true
        return true
    }

    /** Deinitializes the object, releasing any acquired state. */
    fun deinit() {
        rnnoise?.close()
        rnnoise = null
        speexDsp?.close()
        speexDsp = null
        initialized = false
        sampleRate = 0
        quantum = 0
    }

    /**
     * Runs the pre-processor on one frame of 16-bit PCM, modifying it in place.
     *
     * @param samples the frame to process (length must equal [quantum]).
     * @return whether speech was detected by the app's own threshold-driven VAD
     *         ([detectVoice]; always `true` when VAD is disabled). The native
     *         denoisers' internal VAD decisions are deliberately NOT part of
     *         this result: they use fixed probability cutoffs the user cannot
     *         configure, and AND-ing them into the gate made RNNoise + VAD mute
     *         the mic even when the user's own thresholds said "speak".
     */
    fun run(samples: ShortArray): Boolean {
        if (!initialized || samples.size != quantum) return true

        if (denoiseEnabled) {
            when (mode) {
                // The platform NoiseSuppressor effect is applied at the
                // AudioRecord level; nothing to do in software here.
                NoiseSuppressionMode.SYSTEM -> {}
                NoiseSuppressionMode.SPEEX -> applySpeexStage(samples)
                NoiseSuppressionMode.RNNOISE -> {
                    // RNNoise's native processing frame is 480 samples (10 ms
                    // @ 48 kHz) — see rnnoise_get_frame_size() in the
                    // upstream xiph/rnnoise (BSD-3). Any Opus packet size that is
                    // a whole multiple of that 10 ms sub-frame (480/960/1920/2880
                    // = 10/20/40/60 ms) can therefore be handled by RNNoise; the
                    // native side splits each frame into consecutive 10 ms
                    // sub-frames. There is no reason to fall back to Speex for a
                    // bare 10 ms frame — that is exactly one native RNNoise block.
                    if (quantum > 0 && quantum % RnNoiseProcessor.FRAME_SIZE == 0) {
                        // Denoise only; ignore the returned internal-VAD bit.
                        rnnoise?.run(samples)
                    } else {
                        applySpeexStage(samples)
                    }
                }
                NoiseSuppressionMode.SPEEX_RNNOISE -> {
                    // RNNoise first, then the Speex-style spectral subtraction.
                    if (quantum > 0 && quantum % RnNoiseProcessor.FRAME_SIZE == 0) {
                        rnnoise?.run(samples)
                    }
                    applySpeexStage(samples)
                }
            }
        }

        val voice = detectVoice(samples)
        lastDetectedVoice = voice
        return if (vadEnabled) voice else true
    }

    // --- configuration API (mirrors official AudioPreprocessor) ---

    fun usesDenoise(): Boolean = denoiseEnabled
    fun setDenoise(enable: Boolean) {
        denoiseEnabled = enable
        speexDsp?.setDenoise(enable)
    }

    fun usesVAD(): Boolean = vadEnabled
    fun setVAD(enable: Boolean) { vadEnabled = enable }

    /**
     * Legacy single-threshold voice-activity detector (0..100, higher = harder to
     * trigger). For backwards compatibility it also drives the desktop-style
     * [setVADSpeechThreshold]/[setVADSilenceThreshold] to the same value so callers
     * that only use this method keep working with the dual-threshold detector.
     */
    fun setVADThreshold(threshold: Int) {
        val t = threshold.coerceIn(0, 100)
        vadThreshold = t
        vadSpeechThreshold = t
        vadSilenceThreshold = t
    }

    /**
     * Sets the voice-activity detection method, mirroring the desktop Mumble
     * `VADSource` (Amplitude vs. Signal-to-noise).
     */
    fun setVADMethod(method: VadMethod) { vadMethod = method }
    fun getVADMethod(): VadMethod = vadMethod

    /** The speech (voice-activation) threshold in 0..100, mirroring `fVADmax`. */
    fun setVADSpeechThreshold(threshold: Int) {
        vadSpeechThreshold = threshold.coerceIn(0, 100)
        if (vadSilenceThreshold > vadSpeechThreshold) {
            vadSilenceThreshold = vadSpeechThreshold
        }
    }
    fun getVADSpeechThreshold(): Int = vadSpeechThreshold

    /** The silence (voice-deactivation) threshold in 0..100, mirroring `fVADmin`. */
    fun setVADSilenceThreshold(threshold: Int) {
        vadSilenceThreshold = threshold.coerceIn(0, vadSpeechThreshold)
    }
    fun getVADSilenceThreshold(): Int = vadSilenceThreshold

    /**
     * How many 20 ms frames the mic stays open after speech stops (voice hold),
     * mirroring the desktop `iVoiceHold`.
     */
    fun setVADHoldFrames(frames: Int) { vadHoldFrames = frames.coerceAtLeast(0) }
    fun getVADHoldFrames(): Int = vadHoldFrames

    /** The most recent normalised voice level (0..100), for a live VAD meter. */
    fun getVADLevel(): Int = vadLevel.roundToInt()

    /**
     * Enables/disables the speexdsp pre-processor's built-in AGC with the
     * desktop client's adaptation parameters (target 30000, 12/60 dB per
     * second rates) and a user-selectable maximal gain. This replaces the
     * former hand-written Kotlin AGC: gain control now happens either here
     * (native, [AgcMode.SPEEX]) or via the platform AutomaticGainControl
     * effect ([AgcMode.SYSTEM]).
     */
    fun setNativeAgc(enable: Boolean, maxGainDb: Int = 30) {
        val dsp = speexDsp ?: return
        dsp.setAgcTarget(30000)
        dsp.setAgcMaxGain(maxGainDb.coerceIn(0, 90))
        dsp.setAgcIncrement(12)
        dsp.setAgcDecrement(60)
        dsp.setAgc(enable)
    }

    /**
     * Associates an echo canceller state so the preprocessor can use its
     * residual-echo suppression. Pass the raw handle of a
     * [SpeexEchoCanceller], or 0 to detach.
     */
    fun setEchoState(echoHandle: Long) {
        speexDsp?.setEchoState(echoHandle)
    }

    fun usesDereverb(): Boolean = dereverbEnabled
    fun setDereverb(enable: Boolean) { dereverbEnabled = enable }

    /** The noise suppression level in dB (e.g. -15 means -15 dB). */
    fun getNoiseSuppress(): Int = noiseSuppressDb.roundToInt()
    fun setNoiseSuppress(value: Int) {
        noiseSuppressDb = value.toDouble().coerceIn(-60.0, 0.0)
        speexDsp?.setNoiseSuppress(noiseSuppressDb.roundToInt())
    }

    /**
     * Applies the Speex denoise stage through the native speexdsp preprocessor
     * (the desktop client's backend). When the native library is unavailable
     * the frame is passed through unchanged.
     */
    private fun applySpeexStage(samples: ShortArray) {
        val dsp = speexDsp
        if (dsp != null && dsp.isInitialized) {
            dsp.run(samples)
        }
    }

    // --- VAD ---

    private fun detectVoice(samples: ShortArray): Boolean {
        // Compute the normalised voice level (0..100) used by both the decision
        // and the live meter, mirroring the desktop client where
        //   level = (VADSource == SignalToNoise) ? speechProb : (1 + dBlevel/96)
        val level = computeVoiceLevel(samples)
        vadLevel = level

        // Dual-threshold hysteresis: cross fVADmax to start speaking, stay open
        // above fVADmin once already speaking (mirrors the desktop logic).
        var isSpeech = false
        if (level > vadSpeechThreshold) {
            isSpeech = true
        } else if (level > vadSilenceThreshold && vadPreviousVoice) {
            isSpeech = true
        }

        // Voice hold: keep the mic open for a few frames after speech stops so
        // trailing syllables are not clipped.
        if (!isSpeech) {
            vadHoldCounter++
            if (vadHoldCounter < vadHoldFrames) isSpeech = true
        } else {
            vadHoldCounter = 0
        }
        vadPreviousVoice = isSpeech
        return isSpeech
    }

    /**
     * Computes the normalised voice level (0..100) used by the VAD decision,
     * mirroring the desktop Mumble detector:
     *  - AMPLITUDE: based on the clean microphone level (dB below full scale).
     *  - SIGNAL_TO_NOISE: based on a speech-probability proxy computed from the
     *    ratio of the current frame energy to the estimated noise floor.
     */
    private fun computeVoiceLevel(samples: ShortArray): Double {
        var energy = 0.0
        for (s in samples) energy += s.toDouble() * s.toDouble()
        val rms = sqrt(energy / samples.size)
        val levelDb = 20.0 * kotlin.math.log10(rms / 32767.0 + 1e-12)
        // Normalised amplitude level: -96..0 dBFS maps to 0..100.
        val amplitudeLevel = (1.0 + levelDb / 96.0) * 100.0

        if (vadMethod == VadMethod.AMPLITUDE) {
            return amplitudeLevel.coerceIn(0.0, 100.0)
        }

        // Signal-to-noise method: estimate the current frame SNR against the
        // smoothed noise floor, then map a plausible SNR window (0..~40 dB) onto
        // a 0..100 speech-probability-like scale, mirroring fSpeechProb.
        val noiseFloorDb = 20.0 * kotlin.math.log10(trackNoiseFloorRms(rms) / 32767.0 + 1e-12)
        val snrDb = (levelDb - noiseFloorDb).coerceIn(0.0, 40.0)
        val prob = (snrDb / 40.0) * 100.0
        return prob.coerceIn(0.0, 100.0)
    }

    /**
     * Slowly-adapting scalar noise-floor estimate: drops quickly toward quiet
     * frames (noise is tracked within ~a second) and rises only very slowly,
     * so sustained speech does not drag the floor up.
     */
    private fun trackNoiseFloorRms(rms: Double): Double {
        if (!vadNoiseFloorRms.isFinite() || vadNoiseFloorRms <= 0.0) vadNoiseFloorRms = rms
        vadNoiseFloorRms += if (rms < vadNoiseFloorRms) {
            0.10 * (rms - vadNoiseFloorRms)
        } else {
            0.001 * (rms - vadNoiseFloorRms)
        }
        return vadNoiseFloorRms
    }
}
