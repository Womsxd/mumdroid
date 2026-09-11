package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.AppSettings

/**
 * Which parts of the audio configuration actually changed between two
 * [AppSettings] snapshots.
 *
 * Extracted from [VoiceSession.applySettings] so the (subtle) decision table is
 * pure and unit-testable: each flag maps to one reconfiguration action in the
 * voice session, and getting a group wrong either misses an apply (stale
 * capture settings) or needlessly restarts the capture session.
 */
internal data class VoiceSettingsDelta(
    /** Noise suppression / AGC / VAD / input gain or source changed. */
    val noise: Boolean,
    /** Transmit quality, packet duration or low-latency mode changed. */
    val quality: Boolean,
    /** The Opus backend changed, so both codecs must be swapped. */
    val opus: Boolean,
    /** The capture session must be recreated (AEC or mic source changed). */
    val captureSession: Boolean,
    /** PTT/VAD mode changed. */
    val voiceMode: Boolean,
    /** The output route must be re-applied (device order / playback mode). */
    val route: Boolean,
) {
    companion object {
        fun between(previous: AppSettings, next: AppSettings) = VoiceSettingsDelta(
            noise = next.noiseSuppressionEnabled != previous.noiseSuppressionEnabled ||
                next.noiseSuppressionMode != previous.noiseSuppressionMode ||
                next.noiseSuppressionDb != previous.noiseSuppressionDb ||
                next.agcEnabled != previous.agcEnabled ||
                next.agcMode != previous.agcMode ||
                next.agcMaxGainDb != previous.agcMaxGainDb ||
                next.aecEnabled != previous.aecEnabled ||
                next.aecMode != previous.aecMode ||
                next.vadMethod != previous.vadMethod ||
                next.vadSpeechThreshold != previous.vadSpeechThreshold ||
                next.vadSilenceThreshold != previous.vadSilenceThreshold ||
                next.vadHoldFrames != previous.vadHoldFrames ||
                next.inputVolume != previous.inputVolume ||
                next.micSource != previous.micSource,
            quality = next.transmitQuality != previous.transmitQuality ||
                next.framesPerPacket != previous.framesPerPacket ||
                next.lowLatency != previous.lowLatency,
            opus = next.opusImplementation != previous.opusImplementation,
            captureSession = next.aecEnabled != previous.aecEnabled ||
                next.aecMode != previous.aecMode ||
                next.micSource != previous.micSource,
            voiceMode = next.voiceMode != previous.voiceMode,
            route = next.outputDeviceOrder != previous.outputDeviceOrder ||
                next.voicePlaybackMode != previous.voicePlaybackMode ||
                next.micSource != previous.micSource,
        )
    }
}
