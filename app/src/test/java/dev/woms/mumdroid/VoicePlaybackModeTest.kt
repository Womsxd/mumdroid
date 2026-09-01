package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoicePlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackModeTest {

    @Test
    fun defaultsToCommunication() {
        val settings = AppSettings()
        assertEquals(VoicePlaybackMode.COMMUNICATION, settings.voicePlaybackMode)
        assertFalse(settings.anyMediaPlayback())
        assertFalse(settings.usesMediaPlayback(VoiceOutputTarget.HEADSET))
        assertEquals(VoiceOutputTarget.DEFAULT_ORDER, settings.outputDeviceOrder)
    }

    @Test
    fun mediaSharedByHeadsetBluetoothAndSpeaker() {
        val media = AppSettings(voicePlaybackMode = VoicePlaybackMode.MEDIA)
        assertTrue(media.usesMediaPlayback(VoiceOutputTarget.HEADSET))
        assertTrue(media.usesMediaPlayback(VoiceOutputTarget.BLUETOOTH))
        assertTrue(media.usesMediaPlayback(VoiceOutputTarget.SPEAKER))
        assertFalse(
            media.copy(micSource = MicSource.VOICE_COMMUNICATION)
                .usesMediaPlayback(VoiceOutputTarget.HEADSET),
        )
    }

    @Test
    fun earpieceAlwaysCommunication() {
        val media = AppSettings(voicePlaybackMode = VoicePlaybackMode.MEDIA)
        assertEquals(
            VoicePlaybackMode.COMMUNICATION,
            media.playbackModeFor(VoiceOutputTarget.EARPIECE),
        )
        assertFalse(media.usesMediaPlayback(VoiceOutputTarget.EARPIECE))
    }

    @Test
    fun mediaForcesSpeexAecExceptEarpiece() {
        val settings = AppSettings(
            voicePlaybackMode = VoicePlaybackMode.MEDIA,
            aecMode = AecMode.SYSTEM,
        )
        assertEquals(AecMode.SPEEX, settings.effectiveAecMode(VoiceOutputTarget.SPEAKER))
        assertEquals(AecMode.SYSTEM, settings.effectiveAecMode(VoiceOutputTarget.EARPIECE))
        assertEquals(AecMode.SYSTEM, settings.sanitized().aecMode)
    }

    @Test
    fun communicationKeepsSystemAec() {
        val settings = AppSettings(
            voicePlaybackMode = VoicePlaybackMode.COMMUNICATION,
            aecMode = AecMode.SYSTEM,
        )
        assertEquals(AecMode.SYSTEM, settings.effectiveAecMode(VoiceOutputTarget.HEADSET))
        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun sanitizedNormalizesOrder() {
        val settings = AppSettings(
            outputDeviceOrder = listOf(VoiceOutputTarget.SPEAKER),
        )
        assertEquals(
            VoiceOutputTarget.SPEAKER_FIRST_ORDER,
            settings.sanitized().outputDeviceOrder,
        )
    }
}
