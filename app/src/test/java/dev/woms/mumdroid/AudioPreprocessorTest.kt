package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.noise.AudioPreprocessor
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.VadMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPreprocessorTest {

    private val sampleRate = 48000
    private val frameSize = 960 // 20 ms @ 48 kHz

    private fun makeTone(freq: Double, amplitude: Double = 8000.0): ShortArray {
        return ShortArray(frameSize) { i ->
            (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    private fun addWhiteNoise(signal: ShortArray, level: Double): ShortArray {
        val out = ShortArray(signal.size)
        var seed = 12345L
        for (i in signal.indices) {
            // simple LCG noise in [-1, 1]
            seed = (seed * 1103515245 + 12345) and 0x7fffffffL
            val noise = (2.0 * seed / 0x7fffffffL - 1.0) * level
            out[i] = (signal[i] + noise).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt().toShort()
        }
        return out
    }

    private fun rms(s: ShortArray): Double {
        var acc = 0.0
        for (v in s) acc += v.toDouble() * v.toDouble()
        return sqrt(acc / s.size)
    }

    @Test
    fun initAndRun_onTone_doesNotCrash() {
        val pre = AudioPreprocessor()
        assertTrue(pre.init(sampleRate, frameSize))
        pre.setDenoise(true)
        val frame = makeTone(440.0)
        val result = pre.run(frame)
        assertTrue(result)
        pre.deinit()
    }

    @Test
    fun denoise_nativeUnavailable_passesAudioThrough() {
        // In the JVM unit-test environment libspeexdsp.so is not present, so
        // the native speexdsp preprocessor cannot be loaded. The pre-processor
        // must then pass frames through unchanged instead of corrupting them.
        val pre = AudioPreprocessor()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(true)
        pre.setNoiseSuppress(-15)

        val frame = addWhiteNoise(ShortArray(frameSize), 4000.0)
        val before = frame.copyOf()
        pre.run(frame)

        assertArrayEquals(before, frame)
    }

    @Test
    fun setGet_configurationRoundTrip() {
        val pre = AudioPreprocessor()
        pre.setDenoise(true)
        pre.setVAD(true)
        pre.setNoiseSuppress(-20)

        assertTrue(pre.usesDenoise())
        assertTrue(pre.usesVAD())
        assertEquals(-20, pre.getNoiseSuppress())
    }

    @Test
    fun vadThreshold_higherThreshold_requiresLouderSignal() {
        val pre = AudioPreprocessor()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(false)
        pre.setVAD(true)

        // A moderately loud tone (about -33 dBFS) — should be voice at a low
        // threshold but silence at a very high threshold.
        val frame = makeTone(440.0, amplitude = 900.0)

        pre.setVADThreshold(20)
        assertTrue(
            "Expected VAD to trigger at a low threshold",
            pre.run(frame.copyOf())
        )

        // Re-init to clear the voice-hold / hysteresis state so the higher
        // threshold is evaluated on a fresh detector.
        pre.deinit()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(false)
        pre.setVAD(true)
        pre.setVADThreshold(95)
        assertTrue(
            "Expected VAD not to trigger at a high threshold",
            !pre.run(frame.copyOf())
        )
        pre.deinit()
    }

    @Test
    fun vadMethod_amplitudeAndSnrReportSaneLevel() {
        val pre = AudioPreprocessor()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(false)
        pre.setVAD(true)
        pre.setVADSpeechThreshold(50)
        pre.setVADSilenceThreshold(50)
        pre.setVADHoldFrames(0)

        // A loud tone must yield a meaningful level and trigger speech.
        val loud = makeTone(440.0, amplitude = 12000.0)
        pre.setVADMethod(VadMethod.AMPLITUDE)
        assertTrue(pre.run(loud.copyOf()))
        val ampLevel = pre.getVADLevel()
        assertTrue("Amplitude level should be positive, got $ampLevel", ampLevel in 1..100)

        // A near-silent frame must yield a low level and not trigger.
        val quiet = ShortArray(frameSize)
        assertTrue(!pre.run(quiet))
        assertTrue("Silence level should be low", pre.getVADLevel() < 30)

        pre.deinit()
    }

    @Test
    fun vadDualThreshold_hysteresisKeepsVoiceOpenAboveSilence() {
        val pre = AudioPreprocessor()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(false)
        pre.setVAD(true)
        pre.setVADHoldFrames(0)
        // Speech threshold high, silence threshold low: once speech is triggered,
        // a frame above the silence threshold (but below speech) stays open.
        pre.setVADSpeechThreshold(85)
        pre.setVADSilenceThreshold(40)

        // Loud tone crosses the speech threshold.
        val loud = makeTone(440.0, amplitude = 20000.0)
        assertTrue(pre.run(loud.copyOf()))

        // A mid-level frame above silence but below speech should stay open.
        val mid = makeTone(440.0, amplitude = 6000.0)
        assertTrue(
            "Hysteresis should keep voice open above the silence threshold",
            pre.run(mid.copyOf())
        )
        pre.deinit()
    }

    @Test
    fun vadHold_keepsMicOpenAfterSpeechStops() {
        val pre = AudioPreprocessor()
        pre.init(sampleRate, frameSize)
        pre.setDenoise(false)
        pre.setVAD(true)
        pre.setVADHoldFrames(3)
        pre.setVADSpeechThreshold(85)
        pre.setVADSilenceThreshold(40)

        val loud = makeTone(440.0, amplitude = 20000.0)
        val quiet = ShortArray(frameSize)

        assertTrue(pre.run(loud.copyOf()))
        // After speech stops, the hold window keeps it open for the configured
        // number of frames (3 frames -> the first two quiet frames are held).
        assertTrue(pre.run(quiet.copyOf()))
        assertTrue(pre.run(quiet.copyOf()))
        // The hold window has elapsed by the third quiet frame.
        assertTrue(!pre.run(quiet.copyOf()))
        pre.deinit()
    }

    @Test
    fun rnNoiseMode_fallsBackGracefully_whenNativeUnavailable() {
        // In the JVM unit-test environment librnnoise.so is not present, so the
        // native RNNoise engine cannot be loaded. The pre-processor must still
        // construct and process frames without crashing, silently degrading to
        // no-op for the RNNoise stage (its output is left untouched).
        //
        // Exercise the whole supported packet-size range (10/20/40/60 ms) to
        // mirror the Opus frames-per-packet setting, not just the default 20 ms.
        // RNNoise's native frame is 10 ms (480 samples), so 10 ms is handled by
        // RNNoise directly rather than falling back to Speex.
        val frameSizes = listOf(480, 960, 1920, 2880) // 10/20/40/60 ms @ 48 kHz
        for (mode in listOf(
            NoiseSuppressionMode.RNNOISE,
            NoiseSuppressionMode.SPEEX_RNNOISE,
        )) {
            for (fs in frameSizes) {
                val pre = AudioPreprocessor(mode)
                assertTrue("init(fs=$fs) should succeed", pre.init(sampleRate, fs))
                pre.setDenoise(true)
                val frame = addWhiteNoise(makeTone(440.0), 1000.0).let {
                    // Pad/truncate the tone to the requested frame size.
                    ShortArray(fs) { i -> it[i % it.size] }
                }
                val result = pre.run(frame)
                // Must not crash and must return a boolean VAD decision.
                assertTrue("result must be Boolean", result is Boolean)
                // The frame is still valid PCM (clamped shorts), no NaN/OOB.
                for (s in frame) {
                    assertTrue(s >= Short.MIN_VALUE && s <= Short.MAX_VALUE)
                }
                pre.deinit()
            }
        }
    }
}
