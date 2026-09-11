package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.OpusImplementation
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.service.VoiceSettingsDelta
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings delta decides whether a voice setting change restarts capture,
 * re-applies the route or reconfigures the codec. Getting a group wrong either
 * misses an apply or needlessly rebuilds the capture session.
 */
class VoiceSettingsDeltaTest {

    private fun deltaOf(
        previous: AppSettings = AppSettings(),
        next: AppSettings = AppSettings(),
    ) = VoiceSettingsDelta.between(previous, next)

    @Test
    fun identicalSettings_changeNothing() {
        val delta = deltaOf()
        assertFalse(delta.noise)
        assertFalse(delta.quality)
        assertFalse(delta.opus)
        assertFalse(delta.captureSession)
        assertFalse(delta.voiceMode)
        assertFalse(delta.route)
    }

    @Test
    fun transmitQuality_isQualityOnly() {
        val delta = deltaOf(next = AppSettings(transmitQuality = 56))
        assertTrue(delta.quality)
        assertFalse(delta.noise)
        assertFalse(delta.captureSession)
    }

    @Test
    fun framesPerPacket_isQualityOnly() {
        val delta = deltaOf(next = AppSettings(framesPerPacket = 4))
        assertTrue(delta.quality)
        assertFalse(delta.captureSession)
    }

    @Test
    fun opusImplementation_isOpusOnly() {
        val delta = deltaOf(next = AppSettings(opusImplementation = OpusImplementation.CONCENTUS))
        assertTrue(delta.opus)
        assertFalse(delta.quality)
        assertFalse(delta.noise)
    }

    @Test
    fun aecToggle_isNoiseAndCaptureSession() {
        // AEC lives in the capture session: it must restart capture, not just
        // re-apply noise settings.
        val delta = deltaOf(next = AppSettings(aecEnabled = !AppSettings().aecEnabled))
        assertTrue(delta.captureSession)
        assertTrue(delta.noise)
        assertFalse(delta.route)
    }

    @Test
    fun aecMode_isNoiseAndCaptureSession() {
        val other = AecMode.entries.first { it != AppSettings().aecMode }
        val delta = deltaOf(next = AppSettings(aecMode = other))
        assertTrue(delta.captureSession)
        assertTrue(delta.noise)
    }

    @Test
    fun vadThreshold_isNoiseOnly() {
        val delta = deltaOf(next = AppSettings(vadSpeechThreshold = 120))
        assertTrue(delta.noise)
        assertFalse(delta.captureSession)
        assertFalse(delta.quality)
    }

    @Test
    fun voiceMode_isVoiceModeOnly() {
        val other = VoiceMode.entries.first { it != AppSettings().voiceMode }
        val delta = deltaOf(next = AppSettings(voiceMode = other))
        assertTrue(delta.voiceMode)
        assertFalse(delta.noise)
        assertFalse(delta.route)
    }

    @Test
    fun voicePlaybackMode_isRouteOnly() {
        val other = dev.woms.mumdroid.core.model.VoicePlaybackMode
            .entries.first { it != AppSettings().voicePlaybackMode }
        val delta = deltaOf(next = AppSettings(voicePlaybackMode = other))
        assertTrue(delta.route)
        assertFalse(delta.noise)
        assertFalse(delta.captureSession)
    }

    @Test
    fun outputVolume_changeIsNotPartOfAnyGroup() {
        // Volume is applied unconditionally by applySettings.
        val delta = deltaOf(next = AppSettings(outputVolume = 50))
        assertFalse(delta.noise)
        assertFalse(delta.quality)
        assertFalse(delta.opus)
        assertFalse(delta.captureSession)
        assertFalse(delta.voiceMode)
        assertFalse(delta.route)
    }
}
