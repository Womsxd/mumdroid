package dev.woms.mumdroid.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.util.Log
import dev.woms.mumdroid.core.audio.noise.AudioPreprocessor
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.audio.noise.SpeexEchoCanceller
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VadMethod
import kotlin.math.roundToInt

/**
 * Shared microphone capture engine: owns the [AudioRecord] lifecycle, the
 * platform effects (system AGC / AEC / noise suppressor), the software echo
 * canceller and the preprocessing chain (denoise -> native AGC -> VAD ->
 * manual gain).
 *
 * Both the live capture pipeline ([AudioInput]) and the settings level meter
 * ([MicLevelMeter]) drive this engine so their behaviour can never drift:
 * a processing change only ever has to be made here.
 *
 * Usage: configure via [applySettings] / properties, then [open] once,
 * [read] + [processFrame] per frame from one thread and finally [close].
 */
class MicCaptureEngine(
    private val sampleRate: Int = 48000,
) {
    companion object {
        private const val TAG = "MicCaptureEngine"
    }

    // --- state initialised FIRST (the config setters below reference it) ---
    private val preprocessor = AudioPreprocessor()
    private var record: AudioRecord? = null
    private var echoCanceller: SpeexEchoCanceller? = null
    private var systemAgc: AutomaticGainControl? = null
    private var systemAec: android.media.audiofx.AcousticEchoCanceler? = null
    private var systemNs: android.media.audiofx.NoiseSuppressor? = null
    private var preferredDevice: AudioDeviceInfo? = null

    // --- configuration (mirrors the audio section of AppSettings) ---
    var micSource: MicSource = MicSource.MIC
    var noiseSuppressionEnabled: Boolean = true
        set(value) {
            field = value
            preprocessor.setDenoise(value)
        }
    var noiseSuppressionMode: NoiseSuppressionMode = NoiseSuppressionMode.SPEEX
    var noiseSuppressionDb: Int = 15
        set(value) {
            field = value
            preprocessor.setNoiseSuppress(-value)
        }
    var agcMode: AgcMode = AgcMode.SPEEX
    var agcEnabled: Boolean = true
    var agcMaxGainDb: Int = 30
    var aecMode: AecMode = AecMode.SYSTEM
    var aecEnabled: Boolean = false
    var inputVolume: Int = 100
    var vadGating: Boolean = false
    var vadMethod: VadMethod = VadMethod.AMPLITUDE
    var vadSpeechThreshold: Int = 98
    var vadSilenceThreshold: Int = 80
    var vadHoldFrames: Int = 20

    /** The capture/preprocessing frame size in samples. */
    var frameSize: Int = OpusCodec.FRAME_SIZE_10MS * 2
        private set

    var framesPerPacket: Int = 2
        set(value) {
            field = value.coerceIn(1, 6)
            frameSize = OpusCodec.FRAME_SIZE_10MS * field
        }

    /** Whether [open] has succeeded and [close] has not been called. */
    var isOpen: Boolean = false
        private set

    /** Whether individual AEC/AGC/NS settings apply (VOICE_COMMUNICATION handles them itself). */
    private val individualProcessing: Boolean
        get() = micSource == MicSource.MIC

    /**
     * Applies a full configuration snapshot. While open, everything that can
     * be changed live takes effect immediately; structural changes are picked
     * up by the next [open].
     */
    fun applySettings(
        micSource: MicSource = this.micSource,
        noiseEnabled: Boolean = noiseSuppressionEnabled,
        mode: NoiseSuppressionMode = noiseSuppressionMode,
        suppressionDb: Int = noiseSuppressionDb,
        agcMode: AgcMode = this.agcMode,
        agcEnabled: Boolean = this.agcEnabled,
        agcMaxGainDb: Int = this.agcMaxGainDb,
        inputVolume: Int = this.inputVolume,
        aecMode: AecMode = this.aecMode,
        aecEnabled: Boolean = this.aecEnabled,
        vadGating: Boolean = this.vadGating,
        vadMethod: VadMethod = this.vadMethod,
        vadSpeechThreshold: Int = this.vadSpeechThreshold,
        vadSilenceThreshold: Int = this.vadSilenceThreshold,
        vadHoldFrames: Int = this.vadHoldFrames,
        framesPerPacket: Int = this.framesPerPacket,
    ) {
        val denoiseEngineChanged = mode != this.noiseSuppressionMode
        this.micSource = micSource
        noiseSuppressionEnabled = noiseEnabled
        this.noiseSuppressionMode = mode
        this.noiseSuppressionDb = suppressionDb
        this.agcMode = agcMode
        this.agcEnabled = agcEnabled
        this.agcMaxGainDb = agcMaxGainDb
        this.inputVolume = inputVolume
        this.aecMode = aecMode
        this.aecEnabled = aecEnabled
        this.vadGating = vadGating
        this.vadMethod = vadMethod
        this.vadSpeechThreshold = vadSpeechThreshold
        this.vadSilenceThreshold = vadSilenceThreshold
        this.vadHoldFrames = vadHoldFrames
        this.framesPerPacket = framesPerPacket

        if (!isOpen) return

        if (denoiseEngineChanged || preprocessorQuantumChanged()) {
            preprocessor.init(sampleRate, frameSize)
            // Re-associate the echo canceller with the fresh preprocessor
            // state so residual suppression keeps working.
            echoCanceller?.let { preprocessor.setEchoState(it.nativePtr()) }
        }
        syncPreprocessorConfig()
        updatePlatformEffects()
    }

    private fun preprocessorQuantumChanged(): Boolean = preprocessor.isInitialized &&
        !preprocessor.matchesQuantum(frameSize)

    /**
     * Opens the microphone with the configured audio source and attaches the
     * platform effects. @return false when the device could not be opened.
     */
    fun open(): Boolean {
        if (isOpen) return true
        try {
            preprocessor.mode = noiseSuppressionMode
            preprocessor.init(sampleRate, frameSize)
            syncPreprocessorConfig()

            if (individualProcessing && aecEnabled && aecMode == AecMode.SPEEX &&
                SpeexEchoCanceller.isAvailable
            ) {
                val canceller = SpeexEchoCanceller(frameSize, sampleRate)
                if (canceller.start()) {
                    echoCanceller = canceller
                    preprocessor.setEchoState(canceller.nativePtr())
                }
            }

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                when (micSource) {
                    MicSource.MIC -> MediaRecorder.AudioSource.MIC
                    MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                },
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSize * 2 * 8),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                rec.release()
                return false
            }
            rec.setPreferredDevice(preferredDevice)
            record = rec
            updatePlatformEffects()
            // Effects must be attached before recording starts.
            rec.startRecording()
            isOpen = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open microphone", e)
            close()
            return false
        }
    }

    /**
     * Reads one frame of raw PCM into [buffer] (@return samples read; <= 0 on
     * failure). The buffer must be at least [frameSize] long.
     */
    fun read(buffer: ShortArray): Int =
        record?.read(buffer, 0, frameSize) ?: -1
    /**
     * Processes one captured frame in place through the transmission chain:
     * software AEC -> denoiser/native AGC -> VAD -> manual gain.
     *
     * @return whether speech was detected by the VAD (always true when VAD is
     *         disabled). On internal errors the frame passes through unchanged.
     */
    fun processFrame(frame: ShortArray): Boolean {
        if (frame.size != frameSize) return true
        if (echoCanceller != null) {
            echoCanceller?.process(frame)
        }
        val speech = preprocessor.run(frame)
        applyInputGain(frame)
        return speech
    }

    /** The current normalised VAD level (0..100), for a live meter. */
    fun getVadLevel(): Int = preprocessor.getVADLevel()

    /** Latest voice-activity decision, available even when send gating is off. */
    fun lastVoice(): Boolean = preprocessor.lastDetectedVoice

    /**
     * Feeds the speaker reference for the software AEC: called from the
     * playback path with every PCM frame written to the speaker.
     */
    fun pushFarEndFrame(pcm: ShortArray) {
        echoCanceller?.pushFar(pcm)
    }

    /**
     * Pins capture to [device] (headset mic, builtin mic, …). `null` clears
     * the override so the system picks. Safe before [open] and while recording.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        record?.setPreferredDevice(device)
    }

    /** Releases the microphone, all effects and the native engines. */
    fun close() {
        isOpen = false
        echoCanceller?.close()
        echoCanceller = null
        releaseEffect { systemAgc }
        releaseEffect { systemAec }
        releaseEffect { systemNs }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        preprocessor.deinit()
    }

    // --- internals ---

    private fun syncPreprocessorConfig() {
        preprocessor.mode = noiseSuppressionMode
        preprocessor.setDenoise(noiseSuppressionEnabled)
        preprocessor.setNoiseSuppress(-noiseSuppressionDb)
        preprocessor.setNativeAgc(
            individualProcessing && agcEnabled && agcMode == AgcMode.SPEEX, agcMaxGainDb,
        )
        preprocessor.setVAD(vadGating)
        preprocessor.setVADMethod(vadMethod)
        preprocessor.setVADSpeechThreshold(vadSpeechThreshold)
        preprocessor.setVADSilenceThreshold(vadSilenceThreshold)
        preprocessor.setVADHoldFrames(vadHoldFrames)
    }

    private fun updatePlatformEffects() {
        val rec = record
        updateSystemAgc(rec)
        updateSystemAec(rec)
        updateSystemNoiseSuppressor(rec)
    }

    private inline fun releaseEffect(getter: () -> android.media.audiofx.AudioEffect?) {
        try {
            getter()?.release()
        } catch (_: Exception) {
        }
    }

    private fun updateSystemAgc(rec: AudioRecord?) {
        val active = individualProcessing && agcEnabled && agcMode == AgcMode.SYSTEM
        if (!active || rec == null) {
            try {
                systemAgc?.enabled = false
            } catch (_: Exception) {
            }
            return
        }
        try {
            if (systemAgc == null && AutomaticGainControl.isAvailable()) {
                systemAgc = AutomaticGainControl.create(rec.audioSessionId)
            }
            systemAgc?.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach platform AGC", e)
        }
    }

    private fun updateSystemAec(rec: AudioRecord?) {
        val active = individualProcessing && aecEnabled && aecMode == AecMode.SYSTEM
        if (!active || rec == null) {
            try {
                systemAec?.enabled = false
            } catch (_: Exception) {
            }
            return
        }
        try {
            if (systemAec == null &&
                android.media.audiofx.AcousticEchoCanceler.isAvailable()
            ) {
                systemAec = android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)
            }
            systemAec?.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach platform AEC", e)
        }
    }

    private fun updateSystemNoiseSuppressor(rec: AudioRecord?) {
        val active = individualProcessing && noiseSuppressionEnabled &&
            noiseSuppressionMode == NoiseSuppressionMode.SYSTEM
        if (!active || rec == null) {
            try {
                systemNs?.enabled = false
            } catch (_: Exception) {
            }
            return
        }
        try {
            if (systemNs == null && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                systemNs = android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)
            }
            systemNs?.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach platform NS", e)
        }
    }

    /** Applies [inputVolume] (0..200 %) through the soft limiter. */
    private fun applyInputGain(frame: ShortArray) {
        val volume = inputVolume
        if (volume == 100) return
        val gain = volume / 100.0
        for (i in frame.indices) {
            frame[i] = SoftLimiter.limit(frame[i] * gain).roundToInt().toShort()
        }
    }
}
