package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.VoiceTargetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackModeTest {

    @Test
    fun switchPairNeverExpressesAnImpossibleState() {
        // The menu has two switches; the protocol has one three-valued mode.
        assertEquals(LoopbackMode.OFF, LoopbackMode.of(enabled = false, server = false))
        assertEquals(LoopbackMode.LOCAL, LoopbackMode.of(enabled = true, server = false))
        assertEquals(LoopbackMode.SERVER, LoopbackMode.of(enabled = true, server = true))
        // "Server mode while disabled" is not a state: disabling wins, so a
        // stale switch cannot leave a self-test running invisibly.
        assertEquals(LoopbackMode.OFF, LoopbackMode.of(enabled = false, server = true))
    }

    @Test
    fun onlyOffIsInactive() {
        assertFalse(LoopbackMode.OFF.isActive)
        assertTrue(LoopbackMode.LOCAL.isActive)
        assertTrue(LoopbackMode.SERVER.isActive)
    }

    @Test
    fun localModeNeverSendsAndDoesNotTouchTheWhisperTarget() {
        assertTrue(LoopbackMode.LOCAL.isLocal)
        assertFalse(LoopbackMode.LOCAL.isServer)
        // The local branch of the official encoder never reaches the packet
        // builder, so whatever target is passed is irrelevant — it must not be
        // rewritten into the server-loopback id either.
        assertEquals(7, LoopbackMode.LOCAL.outgoingTargetId(7))
        assertEquals(VoiceTargetId.REGULAR_SPEECH, LoopbackMode.LOCAL.outgoingTargetId(0))
    }

    @Test
    fun serverModeStampsTheLoopbackIdOverAnyVoiceTarget() {
        assertTrue(LoopbackMode.SERVER.isServer)
        assertFalse(LoopbackMode.SERVER.isLocal)
        // Official AudioInput::encodeAudioFrame overwrites the packet target
        // after the whisper bookkeeping, so a self-test started while a
        // whisper/shout is active cannot leak to those receivers.
        assertEquals(VoiceTargetId.SERVER_LOOPBACK, LoopbackMode.SERVER.outgoingTargetId(7))
        assertEquals(VoiceTargetId.SERVER_LOOPBACK, LoopbackMode.SERVER.outgoingTargetId(0))
    }

    @Test
    fun offKeepsTheVoiceTargetUntouched() {
        assertEquals(7, LoopbackMode.OFF.outgoingTargetId(7))
        assertEquals(VoiceTargetId.NONE, LoopbackMode.OFF.outgoingTargetId(VoiceTargetId.NONE))
    }
}
