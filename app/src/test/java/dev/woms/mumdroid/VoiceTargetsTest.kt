package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.model.VoiceTargetTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTargetsTest {

    @Test
    fun audioContext_wireValuesMatchTheProtocol() {
        assertEquals(0, AudioContext.NORMAL.wire)
        assertEquals(1, AudioContext.SHOUT.wire)
        assertEquals(2, AudioContext.WHISPER.wire)
        assertEquals(3, AudioContext.LISTEN.wire)
    }

    @Test
    fun audioContext_unknownWireValueIsNotMistakenForNormalSpeech() {
        // A future context must not be shown as plain talking: mapping it to
        // UNKNOWN keeps the decoding honest, and talk state falls back to
        // TALKING exactly like the official default branch.
        assertEquals(AudioContext.UNKNOWN, AudioContext.fromWire(9))
        assertTrue(AudioContext.UNKNOWN.wire < 0)
    }

    @Test
    fun talkState_fromContext() {
        assertEquals(TalkState.SHOUTING, TalkState.fromContext(AudioContext.SHOUT))
        assertEquals(TalkState.WHISPERING, TalkState.fromContext(AudioContext.WHISPER))
        assertEquals(TalkState.TALKING, TalkState.fromContext(AudioContext.NORMAL))
        // Linked-channel traffic and listener delivery look like normal speech.
        assertEquals(TalkState.TALKING, TalkState.fromContext(AudioContext.LISTEN))
    }

    @Test
    fun talkState_passiveIsNotAudible() {
        assertFalse(TalkState.PASSIVE.isAudible)
        assertTrue(TalkState.TALKING.isAudible)
        assertTrue(TalkState.WHISPERING.isAudible)
        assertTrue(TalkState.SHOUTING.isAudible)
    }

    @Test
    fun targetEmptiness() {
        assertTrue(VoiceTargetTarget().isEmpty)
        assertFalse(VoiceTargetTarget(sessions = listOf(3)).isEmpty)
        assertFalse(VoiceTargetTarget(channelId = 0).isEmpty)
    }

    @Test
    fun reservedIds_matchTheProtocol() {
        assertEquals(0, VoiceTargetId.REGULAR_SPEECH)
        assertEquals(31, VoiceTargetId.SERVER_LOOPBACK)
        assertEquals(1, VoiceTargetId.MIN)
        assertEquals(30, VoiceTargetId.MAX)
        assertEquals(-1, VoiceTargetId.NONE)
        // Murmur drops `target < 1 || target >= 0x1f`, so the registered range
        // must sit strictly inside it.
        assertTrue(VoiceTargetId.REGULAR_SPEECH !in VoiceTargetId.RANGE)
        assertTrue(VoiceTargetId.SERVER_LOOPBACK !in VoiceTargetId.RANGE)
    }

    @Test
    fun status_regularSpeechHasNoTarget() {
        val status = VoiceTargetStatus()
        assertTrue(status.isRegular)
        assertFalse(status.isUnavailable)
        assertTrue(status.sessions.isEmpty())
    }

    @Test
    fun status_unavailableTargetIsReported() {
        val status = VoiceTargetStatus(
            spec = VoiceTargetSpec.Users(listOf(5)),
            available = false,
        )
        assertFalse(status.isRegular)
        assertTrue(status.isUnavailable)
        assertEquals(listOf(5), status.sessions)
    }

    @Test
    fun status_channelModifiersAreExposedForTheChip() {
        val status = VoiceTargetStatus(
            spec = VoiceTargetSpec.Channel(2, links = true, children = true),
            channelName = "General",
        )
        assertTrue(status.links)
        assertTrue(status.children)
        assertEquals("General", status.channelName)
    }
}
