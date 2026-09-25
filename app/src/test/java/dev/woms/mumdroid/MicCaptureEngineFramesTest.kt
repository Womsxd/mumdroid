package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.MicCaptureEngine
import dev.woms.mumdroid.core.audio.OpusCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frame-size handling of [MicCaptureEngine] on the JVM-reachable paths: a
 * frames-per-packet change must apply while the engine is closed (the level
 * meter configures it that way before opening) and be coerced to the supported
 * range.
 *
 * The complementary rule — a change is ignored while open so an engine can
 * never end up with a preprocessor paired to an echo state of a different
 * frame size — cannot be exercised here: `isOpen` only becomes true through
 * `open()`, which needs a real `AudioRecord`.
 */
class MicCaptureEngineFramesTest {

    @Test
    fun framesPerPacket_updatesFrameSizeWhileClosed() {
        val engine = MicCaptureEngine(48_000)
        engine.applySettings(framesPerPacket = 4)
        assertEquals(4, engine.framesPerPacket)
        assertEquals(OpusCodec.FRAME_SIZE_10MS * 4, engine.frameSize)
    }

    @Test
    fun framesPerPacket_isCoercedToTheSupportedRange() {
        val engine = MicCaptureEngine(48_000)
        engine.applySettings(framesPerPacket = 99)
        assertEquals(6, engine.framesPerPacket)
        assertEquals(OpusCodec.FRAME_SIZE_10MS * 6, engine.frameSize)
    }
}
