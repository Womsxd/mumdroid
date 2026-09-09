package dev.woms.mumdroid.service

import android.media.AudioDeviceInfo
import dev.woms.mumdroid.core.audio.AudioInput
import dev.woms.mumdroid.core.audio.AudioOutput
import dev.woms.mumdroid.core.audio.OpusImplementation
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceMode

/**
 * Owns the live capture and playback endpoints: their creation, settings,
 * preferred devices and the capture PCM/VAD taps. Route policy stays in
 * [VoiceRouteController]; transmission decisions and talk state come back
 * through [Host].
 */
internal class VoiceAudioEndpoints(
    private val routeController: VoiceRouteController,
    private val host: Host,
) {
    interface Host {
        fun settings(): AppSettings
        fun localSession(): Int
        fun isLocallyBlocked(session: Int): Boolean
        fun shouldSuppressIncoming(): Boolean
        fun shouldTransmit(): Boolean
        fun effectiveFramesPerPacket(): Int
        fun vadGating(): Boolean
        fun onCaptureStarting()
        fun onPcmFrame(pcm: ShortArray)
        fun onSpeechDetected(active: Boolean)
        fun onVadLevel(level: Int)
        fun setUserTalking(session: Int, talking: Boolean)
    }

    private var audioInput: AudioInput? = null
    private var audioOutput: AudioOutput? = null

    val inputLive: Boolean get() = audioInput != null
    val outputLive: Boolean get() = audioOutput != null

    /** Creates the playback endpoint for the current media route. */
    fun openOutput() {
        audioOutput = createAudioOutput(routeController.currentMediaUsage())
    }

    fun stop() {
        audioInput?.stop()
        audioInput = null
        audioOutput?.stop()
        audioOutput = null
    }

    /** Deafen: silence playback but keep the endpoint for undeafen. */
    fun pauseOutput() {
        audioOutput?.stop()
    }

    /** Undeafen: resume the existing playback endpoint. */
    fun resumeOutput() {
        audioOutput?.start()
    }

    fun startCapture() {
        if (audioInput != null) return
        host.onCaptureStarting()
        audioInput = AudioInput().apply {
            applyCaptureSettingsTo(this)
            setPreferredDevice(routeController.playbackRouter.inputDevice)
            start(object : AudioInput.Sink {
                override fun onPcmFrame(pcm: ShortArray) {
                    if (host.shouldTransmit()) host.onPcmFrame(pcm)
                }

                override fun onSpeechDetected(active: Boolean) {
                    host.onSpeechDetected(active)
                }

                override fun onVadLevel(level: Int) {
                    host.onVadLevel(level)
                }
            })
        }
    }

    fun restartCapture() {
        audioInput?.stop()
        audioInput = null
        startCapture()
    }

    fun restartCaptureIfLive() {
        if (audioInput != null) restartCapture()
    }

    /** Re-applies capture settings in place (no capture restart). */
    fun applyCaptureSettingsIfLive() {
        audioInput?.let { applyCaptureSettingsTo(it) }
    }

    /** Applies the audio-processing settings to a live capture endpoint. */
    fun applyNoiseSettings(settings: AppSettings) {
        audioInput?.applySettings(
            noiseEnabled = settings.noiseSuppressionEnabled,
            mode = settings.noiseSuppressionMode,
            suppressionDb = settings.noiseSuppressionDb,
            agcMode = settings.agcMode,
            agcEnabled = settings.agcEnabled,
            agcMaxGainDb = settings.agcMaxGainDb,
            inputVolume = settings.inputVolume,
            aecMode = activeAecMode(),
            aecEnabled = settings.aecEnabled,
            micSource = settings.micSource,
            vadGating = settings.voiceMode == VoiceMode.VAD,
            vadMethod = settings.vadMethod,
            vadSpeechThreshold = settings.vadSpeechThreshold,
            vadSilenceThreshold = settings.vadSilenceThreshold,
            vadHoldFrames = settings.vadHoldFrames,
        )
    }

    fun setVolume(volume: Int) {
        audioOutput?.volume = volume
    }

    fun setOpusImplementation(implementation: OpusImplementation) {
        audioOutput?.setOpusImplementation(implementation)
    }

    fun writePacket(session: Int, frameNumber: Long, payload: ByteArray, isLastFrame: Boolean) {
        audioOutput?.writePacket(session, frameNumber, payload, isLastFrame)
    }

    fun onOutputMediaChanged() {
        audioOutput?.stop()
        audioOutput = createAudioOutput(routeController.currentMediaUsage())
    }

    fun onAecChanged() {
        restartCapture()
    }

    fun onInputDevice(device: AudioDeviceInfo?) {
        audioInput?.setPreferredDevice(device)
    }

    fun onOutputDevice(device: AudioDeviceInfo?) {
        audioOutput?.setPreferredDevice(device)
    }

    private fun applyCaptureSettingsTo(input: AudioInput) {
        val settings = host.settings()
        input.applySettings(
            noiseEnabled = settings.noiseSuppressionEnabled,
            mode = settings.noiseSuppressionMode,
            suppressionDb = settings.noiseSuppressionDb,
            agcMode = settings.agcMode,
            agcEnabled = settings.agcEnabled,
            agcMaxGainDb = settings.agcMaxGainDb,
            inputVolume = settings.inputVolume,
            aecMode = activeAecMode(),
            aecEnabled = settings.aecEnabled,
            micSource = settings.micSource,
            vadGating = host.vadGating(),
            vadMethod = settings.vadMethod,
            vadSpeechThreshold = settings.vadSpeechThreshold,
            vadSilenceThreshold = settings.vadSilenceThreshold,
            vadHoldFrames = settings.vadHoldFrames,
            framesPerPacket = host.effectiveFramesPerPacket(),
        )
    }

    private fun createAudioOutput(media: Boolean): AudioOutput {
        val settings = host.settings()
        return AudioOutput(
            mediaUsage = media,
            opusImplementation = settings.opusImplementation,
        ).apply {
            volume = settings.outputVolume
            echoReferenceTap = { pcm -> audioInput?.pushFarEndFrame(pcm) }
            speakerIdleTap = { session -> host.setUserTalking(session, false) }
            speakerTalkingTap = { session, talking ->
                if (session != host.localSession()) host.setUserTalking(session, talking)
            }
            setPreferredDevice(routeController.playbackRouter.outputDevice)
            start()
        }
    }

    private fun activeAecMode(): AecMode {
        val target = routeController.currentTarget() ?: return host.settings().aecMode
        return host.settings().effectiveAecMode(target)
    }
}
