package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.noise.AudioPreprocessor
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the three transmission modes (continuous / voice-activity / PTT)
 * and the defaults of the voice-mode setting, plus the voice-activity detector
 * that underpins the VAD (voice-activated) transmission mode.
 */
class VoiceModeTest {

    /** Continuous speaking must be the default transmission mode. */
    @Test
    fun voiceMode_defaultsToContinuous() {
        assertEquals(VoiceMode.CONTINUOUS, AppSettings().voiceMode)
    }

    /** All three transmission modes must be enumerable. */
    @Test
    fun voiceMode_allModesPresent() {
        assertEquals(
            setOf(VoiceMode.CONTINUOUS, VoiceMode.VAD, VoiceMode.PTT),
            VoiceMode.entries.toSet(),
        )
    }

    /** The setting round-trips through a copy. */
    @Test
    fun voiceMode_roundTripsThroughSettingsCopy() {
        assertEquals(
            VoiceMode.PTT,
            AppSettings().copy(voiceMode = VoiceMode.PTT).voiceMode,
        )
        assertEquals(
            VoiceMode.VAD,
            AppSettings().copy(voiceMode = VoiceMode.VAD).voiceMode,
        )
    }

    /**
     * The voice-activity detector that gates frames in VAD mode reports speech
     * for a loud frame and silence for a quiet one (matching AudioPreprocessor's
     * `run` return value used by AudioInput's vad-gating path).
     */
    @Test
    fun vadDetector_distinguishesSpeechFromSilence() {
        val pre = AudioPreprocessor(NoiseSuppressionMode.SPEEX)
        pre.init(48000, 960)
        pre.setVAD(true)
        pre.setVADThreshold(50)
        pre.setVADHoldFrames(0)
        pre.setDenoise(false)

        // A loud, non-constant frame should be classified as speech.
        val loud = ShortArray(960)
        var phase = 0.0
        for (i in loud.indices) {
            phase += 0.05
            loud[i] = (12000 * kotlin.math.sin(phase)).toInt().toShort()
        }
        assertTrue("Loud frame should be detected as speech", pre.run(loud))

        // A near-silent frame should be classified as silence.
        val quiet = ShortArray(960)
        assertFalse("Quiet frame should not be detected as speech", pre.run(quiet))
    }

    @Test
    fun outputDeviceOrder_defaultsToHeadsetThenEarpiece() {
        assertEquals(
            VoiceOutputTarget.DEFAULT_ORDER,
            AppSettings().outputDeviceOrder,
        )
    }

    @Test
    fun earpieceProximityFade_defaultsOn() {
        assertTrue(AppSettings().earpieceProximityFade)
    }
}
