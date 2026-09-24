package dev.woms.mumdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.woms.mumdroid.core.audio.noise.RnNoiseProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-place contract of the RNNoise JNI binding.
 *
 * `nativeProcess` takes a single array now and writes the denoised samples back
 * over it (each 10 ms sub-frame is copied into the native float scratch before
 * the result is written back), which removed the per-frame output array and the
 * copy-back from the audio hot path. Nothing observable changes on the JVM —
 * where `librnnoise.so` is absent the handle stays 0 and `run()` is a
 * passthrough — so the processing itself has to be checked where the library is
 * loaded.
 */
@RunWith(AndroidJUnit4::class)
class RnNoiseProcessorInPlaceTest {

    @Test
    fun run_denoisesTheFrameInPlace() {
        assumeTrue(
            "librnnoise.so not loadable on this device",
            RnNoiseProcessor.nativeFrameSize > 0,
        )

        val dsp = RnNoiseProcessor()
        try {
            assertTrue("native RNNoise state was not created", dsp.isInitialized)

            // Two 10 ms sub-frames of loud noise. RNNoise attenuates it, so a
            // changed frame means the single array really was processed in
            // place; a passthrough would leave it byte-identical.
            val frame = noise(2 * RnNoiseProcessor.FRAME_SIZE)
            val before = frame.copyOf()

            dsp.run(frame)

            assertFalse(
                "the frame must be denoised in place, not passed through",
                frame.contentEquals(before),
            )
        } finally {
            dsp.close()
        }
    }

    private fun noise(size: Int): ShortArray {
        var seed = 12345L
        return ShortArray(size) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffffL
            (seed % 20001L - 10000L).toShort()
        }
    }
}
