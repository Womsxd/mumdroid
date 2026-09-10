package dev.woms.mumdroid

import com.google.protobuf.MessageLite
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.net.MessageType
import dev.woms.mumdroid.core.proto.VoiceTarget
import dev.woms.mumdroid.service.SessionRoster
import dev.woms.mumdroid.service.VoiceTargetController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTargetControllerTest {

    private class Sent(val type: Int, val message: VoiceTarget)

    private fun controller(): Pair<VoiceTargetController, MutableList<Sent>> {
        val roster = SessionRoster(CoroutineScope(Dispatchers.Unconfined))
        val sent = mutableListOf<Sent>()
        val controller = VoiceTargetController(roster)
        controller.send = { type: Int, message: MessageLite ->
            sent.add(Sent(type, message as VoiceTarget))
        }
        return controller to sent
    }

    private fun seedUser(session: Int, name: String): Pair<VoiceTargetController, MutableList<Sent>> {
        val (controller, sent) = controller()
        val roster = rosterOf(controller)
        roster.userMap[session] = User(session = session, name = name)
        return controller to sent
    }

    /** Reaches the roster the controller was built with (same instance). */
    private fun rosterOf(controller: VoiceTargetController): SessionRoster {
        val field = VoiceTargetController::class.java.getDeclaredField("roster")
        field.isAccessible = true
        return field.get(controller) as SessionRoster
    }

    @Test
    fun nullSpec_restoresRegularSpeechAndRevokesTheRegistration() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        sent.clear()
        controller.setSpec(null)
        assertEquals(VoiceTargetId.REGULAR_SPEECH, controller.sendTargetId)
        assertTrue(controller.status.value.isRegular)
        // The server keeps a registration until it is cleared, so restoring
        // regular speech must revoke it.
        assertEquals(1, sent.size)
        assertEquals(0, sent[0].message.targetsCount)
    }

    @Test
    fun clearOnAnIdleController_sendsNothing() {
        val (controller, sent) = seedUser(1, "a")
        controller.clear()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun userSpec_registersOnTheControlChannel() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        assertEquals(1, controller.sendTargetId)
        val message = sent.single()
        assertEquals(MessageType.VOICE_TARGET, message.type)
        assertEquals(1, message.message.id)
        assertEquals(listOf(1), message.message.getTargets(0).sessionList)
    }

    @Test
    fun channelSpec_carriesFlagsAndName() {
        val (controller, sent) = seedUser(1, "a")
        rosterOf(controller).putChannel(
            dev.woms.mumdroid.core.model.Channel(id = 4, name = "General"),
        )
        controller.setSpec(VoiceTargetSpec.Channel(4, links = true, children = true))
        val target = sent.single().message.getTargets(0)
        assertEquals(4, target.channelId)
        assertTrue(target.links)
        assertTrue(target.children)
        assertEquals("General", controller.status.value.channelName)
    }

    @Test
    fun repeatedSameSpec_doesNotReregister() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        assertEquals(1, sent.size)
    }

    @Test
    fun vanishedTargetUser_stopsTransmittingAndIsFlagged() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        sent.clear()

        // The target user leaves: the controller must refuse to send rather
        // than let the whisper degrade into a broadcast.
        rosterOf(controller).userMap.remove(1)
        controller.refresh()
        assertEquals(VoiceTargetId.NONE, controller.sendTargetId)
        assertTrue(controller.status.value.isUnavailable)
        // The receiver that vanished is still named, so the chip can say what
        // the user picked even though nothing is being sent.
        assertEquals(listOf("a"), controller.status.value.userNames)
        assertEquals(null, controller.activeTalkState())
        // Only an explicit choice revives speaking, on a freshly registered id.
        controller.setSpec(VoiceTargetSpec.Users(listOf(2)))
        rosterOf(controller).userMap[2] = User(session = 2, name = "b")
        controller.setSpec(VoiceTargetSpec.Users(listOf(2)))
        assertEquals(1, controller.sendTargetId)
        assertEquals(listOf(2), sent.last().message.getTargets(0).sessionList)
    }

    @Test
    fun refreshAfterShrinkingTarget_revokesTheOldRegistration() {
        val (controller, sent) = seedUser(1, "a")
        rosterOf(controller).userMap[2] = User(session = 2, name = "b")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1, 2)))
        val firstId = controller.sendTargetId
        sent.clear()

        rosterOf(controller).userMap.remove(2)
        controller.refresh()
        // The stale registration is revoked in the same ordered control channel
        // that installs the narrower receiver set.
        assertEquals(2, sent.size)
        assertEquals(firstId, sent[0].message.id)
        assertEquals(0, sent[0].message.targetsCount)
        val register = sent[1].message
        assertEquals(listOf(1), register.getTargets(0).sessionList)
    }

    @Test
    fun clear_revokesEveryRegistrationAndRestoresRegularSpeech() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        sent.clear()
        controller.clear()
        assertEquals(1, sent.size)
        assertEquals(MessageType.VOICE_TARGET, sent[0].type)
        assertEquals(0, sent[0].message.targetsCount)
        assertEquals(VoiceTargetId.REGULAR_SPEECH, controller.sendTargetId)
        assertTrue(controller.status.value.isRegular)
    }

    @Test
    fun reset_sendsNothing() {
        val (controller, sent) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        sent.clear()
        controller.reset()
        assertTrue(sent.isEmpty())
        assertEquals(VoiceTargetId.REGULAR_SPEECH, controller.sendTargetId)
    }

    @Test
    fun activeTalkState_followsTheTargetShape() {
        val (controller, _) = seedUser(1, "a")
        assertEquals(TalkState.TALKING, controller.activeTalkState())
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        assertEquals(TalkState.WHISPERING, controller.activeTalkState())
        rosterOf(controller).putChannel(
            dev.woms.mumdroid.core.model.Channel(id = 4, name = "General"),
        )
        controller.setSpec(VoiceTargetSpec.Channel(4))
        assertEquals(TalkState.SHOUTING, controller.activeTalkState())
        controller.setSpec(null)
        assertEquals(TalkState.TALKING, controller.activeTalkState())
    }

    @Test
    fun activeTalkState_isNullWhileTheTargetCannotSend() {
        val (controller, _) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        rosterOf(controller).userMap.remove(1)
        controller.refresh()
        // Audio is withheld, so no talking indicator may be shown either.
        assertEquals(null, controller.activeTalkState())
    }

    @Test
    fun targetThatLostReceivers_doesNotBecomeAvailableAgainOnRosterGrowth() {
        val (controller, _) = seedUser(1, "a")
        controller.setSpec(VoiceTargetSpec.Users(listOf(1)))
        rosterOf(controller).userMap.remove(1)
        controller.refresh()
        assertFalse(controller.status.value.available)
        rosterOf(controller).userMap[1] = User(session = 1, name = "a")
        controller.refresh()
        // Re-binding by session number alone would silently retarget speech
        // after a reconnect where the id was reused by somebody else.
        assertFalse(controller.status.value.available)
    }
}
