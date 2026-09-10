package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.service.AudioLoopback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoopbackTest {

    private val played = mutableListOf<Pair<Int, ShortArray>>()

    private fun loopback(session: Int = 42) = AudioLoopback(
        localSession = { session },
        play = { s, pcm -> played.add(s to pcm) },
    )

    @Test
    fun startsOff() {
        val loop = loopback()
        assertEquals(LoopbackMode.OFF, loop.current)
        assertEquals(VoiceTargetId.REGULAR_SPEECH, loop.outgoingTargetId(0))
    }

    @Test
    fun setReportsWhetherTheModeActuallyChanged() {
        val loop = loopback()
        assertTrue(loop.set(LoopbackMode.LOCAL))
        // Idempotent writes must not make the caller flush an utterance.
        assertFalse(loop.set(LoopbackMode.LOCAL))
        assertTrue(loop.set(LoopbackMode.SERVER))
        assertEquals(LoopbackMode.SERVER, loop.mode.value)
        assertTrue(loop.set(LoopbackMode.OFF))
    }

    @Test
    fun localModePlaysTheFrameBackAndConsumesIt() {
        val loop = loopback(session = 7)
        loop.set(LoopbackMode.LOCAL)
        val pcm = ShortArray(960) { 5 }
        assertTrue(loop.loopLocal(pcm))
        assertEquals(1, played.size)
        assertEquals(7, played[0].first)
        assertSame(pcm, played[0].second)
    }

    @Test
    fun otherModesLeaveTheFrameToTheTransmitter() {
        val loop = loopback()
        val pcm = ShortArray(960)
        assertFalse(loop.loopLocal(pcm))
        loop.set(LoopbackMode.SERVER)
        // The server mode still transmits: murmur echoes it back to this client
        // only, so the frame has to leave the device.
        assertFalse(loop.loopLocal(pcm))
        assertTrue(played.isEmpty())
    }

    @Test
    fun serverModeStampsTheLoopbackTarget() {
        val loop = loopback()
        loop.set(LoopbackMode.SERVER)
        assertEquals(VoiceTargetId.SERVER_LOOPBACK, loop.outgoingTargetId(3))
    }

    @Test
    fun resetDropsTheModeForTheNextSession() {
        val loop = loopback()
        loop.set(LoopbackMode.SERVER)
        loop.reset()
        assertEquals(LoopbackMode.OFF, loop.current)
        // A leftover self-test would silence the next connection.
        assertFalse(loop.loopLocal(ShortArray(960)))
    }
}
