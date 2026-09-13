package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.net.VoiceSendChannel
import dev.woms.mumdroid.service.TalkStateController
import dev.woms.mumdroid.service.VoiceTransmitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transmission state machine: PTT / VAD / continuous gating, the reported
 * talk state and the half-duplex incoming-suppression rule. Pure logic driven
 * through a real transmitter over a fake send channel.
 */
class TalkStateControllerTest {

    private class FakeChannel : VoiceSendChannel {
        var silenceEncodes = 0
        private var seq = 0

        override fun encodeOpus(pcm: ShortArray): ByteArray {
            seq++
            return byteArrayOf(seq.toByte())
        }

        override fun resetEncoder() {}

        override fun encodeSilence(): Pair<ByteArray, Int> {
            silenceEncodes++
            return byteArrayOf(0) to 1
        }

        override fun buildTunnelPacket(
            payload: ByteArray,
            isLastFrame: Boolean,
            frameCount: Int,
            target: Int,
        ): ByteArray = payload.copyOf() + byteArrayOf(if (isLastFrame) 1 else 0)

        override val isRunning: Boolean get() = true
        override fun isCryptoReady(): Boolean = false
        override fun sendPlaintextUdp(body: ByteArray): Boolean = false
    }

    private val channel = FakeChannel()
    private val reported = mutableListOf<Pair<Int, TalkState>>()
    private var blocked = false
    private var localSession = 7
    private var localTalkState: TalkState? = TalkState.TALKING

    private fun controller() = TalkStateController(
        transmitter = VoiceTransmitter(
            channel = { channel },
            useTcp = { true },
            sendTunneled = {},
            targetId = { 0 },
            withholdAudio = { false },
        ),
        isTransmitBlocked = { blocked },
        localSession = { localSession },
        localTalkState = { localTalkState },
        setUserTalkState = { session, state -> reported += session to state },
    )

    @Test
    fun ptt_transmitsOnlyWhileHeld() {
        val c = controller()
        c.setVoiceMode(VoiceMode.PTT)

        assertFalse(c.shouldTransmit())
        c.startTalking()
        assertTrue(c.pttHeld)
        assertTrue(c.talking.value)
        assertTrue(c.shouldTransmit())

        c.stopTalking()
        assertFalse(c.pttHeld)
        assertFalse(c.talking.value)
        assertFalse(c.shouldTransmit())
        assertEquals(1, channel.silenceEncodes)
        assertEquals(
            listOf(7 to TalkState.TALKING, 7 to TalkState.PASSIVE),
            reported,
        )
    }

    @Test
    fun ptt_blockedKeyPressIsIgnored() {
        blocked = true
        val c = controller()
        c.setVoiceMode(VoiceMode.PTT)

        c.startTalking()

        assertFalse(c.pttHeld)
        assertFalse(c.talking.value)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun continuousTransmitsUnlessBlocked() {
        val c = controller()
        c.setVoiceMode(VoiceMode.CONTINUOUS)
        assertTrue(c.shouldTransmit())

        blocked = true
        assertFalse(c.shouldTransmit())
    }

    @Test
    fun applyVoiceModeChange_toContinuousStartsTalking() {
        val c = controller()
        c.setVoiceMode(VoiceMode.CONTINUOUS)

        c.applyVoiceModeChange()

        assertTrue(c.talking.value)
        assertEquals(7 to TalkState.TALKING, reported.last())
    }

    @Test
    fun applyVoiceModeChange_toPttWithoutTheKeyEndsTransmission() {
        val c = controller()
        c.setVoiceMode(VoiceMode.CONTINUOUS)
        c.startContinuousTalking()
        assertTrue(c.talking.value)

        c.setVoiceMode(VoiceMode.PTT)
        c.applyVoiceModeChange()

        assertFalse(c.talking.value)
        assertFalse(c.pttHeld)
        assertEquals(7 to TalkState.PASSIVE, reported.last())
    }

    @Test
    fun vad_tracksSpeechDetected() {
        val c = controller()
        c.setVoiceMode(VoiceMode.VAD)

        c.onSpeechDetected(true)
        assertTrue(c.talking.value)
        assertEquals(7 to TalkState.TALKING, reported.last())

        c.onSpeechDetected(false)
        assertFalse(c.talking.value)
        assertEquals(7 to TalkState.PASSIVE, reported.last())
        assertTrue("falling edge must terminate the utterance", channel.silenceEncodes >= 1)
    }

    @Test
    fun vadLevel_onlyFollowsActivityInVadMode() {
        val c = controller()
        c.setVoiceMode(VoiceMode.CONTINUOUS)
        c.onVadLevel(42)
        assertEquals(0, c.vadLevel.value)

        c.setVoiceMode(VoiceMode.VAD)
        c.onVadLevel(42)
        assertEquals(42, c.vadLevel.value)
    }

    @Test
    fun endTransmission_reportsPassiveAndClearsTheMeter() {
        val c = controller()
        c.setVoiceMode(VoiceMode.VAD)
        c.onVadLevel(9)
        c.onSpeechDetected(true)
        reported.clear()

        c.endTransmission()

        assertEquals(0, c.vadLevel.value)
        assertFalse(c.talking.value)
        assertEquals(listOf(7 to TalkState.PASSIVE), reported)
    }

    @Test
    fun halfDuplex_suppressesIncomingOnlyOutsideContinuousWhileTalking() {
        val c = controller()
        c.setHalfDuplex(true)

        c.setVoiceMode(VoiceMode.CONTINUOUS)
        c.startContinuousTalking()
        assertFalse("continuous mode is never suppressed", c.shouldSuppressIncoming())

        c.setVoiceMode(VoiceMode.VAD)
        c.onSpeechDetected(true)
        assertTrue(c.shouldSuppressIncoming())

        c.endTransmission()
        assertFalse(c.shouldSuppressIncoming())
    }

    @Test
    fun halfDuplexDisabled_neverSuppresses() {
        val c = controller()
        c.setVoiceMode(VoiceMode.VAD)
        c.onSpeechDetected(true)

        assertFalse(c.shouldSuppressIncoming())
    }

    @Test
    fun reportedState_reflectsTheActiveVoiceTarget() {
        localTalkState = TalkState.WHISPERING
        val c = controller()
        c.setVoiceMode(VoiceMode.CONTINUOUS)
        c.startContinuousTalking()
        assertEquals(7 to TalkState.WHISPERING, reported.last())

        // A target that cannot send anything reports as passive, not talking.
        localTalkState = null
        localSession = 9
        c.endTransmission()
        assertEquals(9 to TalkState.PASSIVE, reported.last())
    }

    @Test
    fun resetForStop_clearsWithoutNotifyingTheRoster() {
        val c = controller()
        c.setVoiceMode(VoiceMode.VAD)
        c.onSpeechDetected(true)
        val before = reported.size

        c.resetForStop()

        assertFalse(c.talking.value)
        assertEquals(0, c.vadLevel.value)
        assertFalse(c.pttHeld)
        assertEquals(before, reported.size)
    }
}
