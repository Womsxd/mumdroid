package dev.woms.mumdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.woms.mumdroid.core.audio.noise.SpeexDspProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-length contract of the speexdsp preprocessor JNI wrapper.
 *
 * The native state is initialised for one fixed frame size and
 * `speex_preprocess_run()` processes exactly that many samples at the buffer
 * *in place* (reads and writes `x[0..frame_size-1]`), so a frame of any other
 * length would access memory past the end of the Java array. The guard is
 * deliberate at both layers — the Kotlin entry and the JNI entry.
 *
 * This can only be exercised where `libspeexdsp.so` is loaded: in the JVM unit
 * tests the library is absent, the wrapper's handle stays 0 and `run()` returns
 * false for every input, so those tests cannot tell a rejected frame from a
 * processed one. Hence the instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class SpeexDspProcessorFrameSizeTest {

    private val sampleRate = 48000
    private val frameSize = 960 // 20 ms @ 48 kHz

    @Test
    fun run_rejectsEveryFrameLengthOtherThanTheCreatedOne() {
        assumeTrue("libspeexdsp.so not loadable on this device", SpeexDspProcessor.isAvailable)

        val dsp = SpeexDspProcessor(frameSize, sampleRate)
        try {
            assertTrue("native preprocessor state was not created", dsp.isInitialized)

            // VAD is disabled inside the native state (nativeCreate turns it
            // off), and speexdsp reports 1 for every processed frame in that
            // case (preprocess.c, speex_preprocess_run), so a frame of the
            // created length must come back as processed.
            assertTrue(
                "a frame of the created length must be processed",
                dsp.run(ShortArray(frameSize)),
            )

            // Any other length is rejected, and because the guard runs before
            // the buffer is handed to native code the frame is left untouched.
            for (size in listOf(0, 1, frameSize / 2, frameSize - 1, frameSize + 1, frameSize * 2)) {
                val frame = ShortArray(size) { (it % 100 + 1).toShort() }
                val before = frame.copyOf()
                assertFalse("frame of size $size must be rejected", dsp.run(frame))
                assertArrayEquals("frame of size $size must be left untouched", before, frame)
            }
        } finally {
            dsp.close()
        }
    }
}
