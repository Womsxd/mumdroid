package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ServerRemovalKind
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.proto.PermissionDenied
import dev.woms.mumdroid.service.SessionChat
import dev.woms.mumdroid.service.SessionNotices
import dev.woms.mumdroid.service.SessionRoster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Join / move / kick / permission-denied system lines. The chat log is driven
 * on an unconfined scope so `appendSystem` lands synchronously.
 */
class SessionNoticesTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val chat = SessionChat(scope)
    private val roster = SessionRoster(scope)

    /** Deterministic strings: the id and arguments are echoed back. */
    private val strings = object : SessionNotices.Strings {
        override fun getString(id: Int): String = "s$id"
        override fun getString(id: Int, vararg formatArgs: Any): String =
            "s$id(" + formatArgs.joinToString(",") + ")"
    }

    private fun notices(serverName: String = "Srv") =
        SessionNotices(chat, roster, { serverName }, strings)

    private fun systemTexts() = chat.messages.value.filter { it.isSystem }.map { it.text }

    private fun setUpRoster() {
        roster.localSession = 1
        roster.putChannel(Channel(id = 1, name = "Root"))
        roster.putChannel(Channel(id = 2, name = "Child"))
        roster.userMap[1] = User(session = 1, name = "Me", channelId = 1, isLocalUser = true)
        roster.userMap[2] = User(session = 2, name = "Admin", channelId = 1)
    }

    @Test
    fun system_appendsAServerNamedSystemLine() {
        notices(serverName = "Srv").system("hello")

        val message = chat.messages.value.single()
        assertTrue(message.isSystem)
        assertEquals("Srv", message.actorName)
        assertEquals("hello", message.text)
    }

    @Test
    fun announceChannelChange_localFirstJoinNamesTheChannel() {
        setUpRoster()

        notices().announceChannelChange(
            protoSession = 1,
            newChannel = 2,
            existing = null,
            updatedName = "Me",
            restorePending = false,
            consumePasswordJoin = { false },
        )

        assertEquals(
            listOf(strings.getString(R.string.chat_user_joined, "Me", "Child")),
            systemTexts(),
        )
    }

    @Test
    fun announceChannelChange_localRestoreSuppressesTheJoinLine() {
        setUpRoster()

        notices().announceChannelChange(
            protoSession = 1,
            newChannel = 2,
            existing = null,
            updatedName = "Me",
            restorePending = true,
            consumePasswordJoin = { false },
        )

        assertTrue(systemTexts().isEmpty())
    }

    @Test
    fun announceChannelChange_localMoveNeedsTheConsumedPasswordJoin() {
        setUpRoster()
        val existing = roster.userMap[1]!!

        notices().announceChannelChange(
            protoSession = 1,
            newChannel = 2,
            existing = existing,
            updatedName = "Me",
            restorePending = false,
            consumePasswordJoin = { false },
        )
        assertTrue(systemTexts().isEmpty())

        notices().announceChannelChange(
            protoSession = 1,
            newChannel = 2,
            existing = existing,
            updatedName = "Me",
            restorePending = false,
            consumePasswordJoin = { true },
        )
        assertEquals(
            listOf(strings.getString(R.string.chat_user_moved, "Me", "Root", "Child")),
            systemTexts(),
        )
    }

    @Test
    fun announceChannelChange_remoteHintsOnlyWhenEnabledAndInMyChannel() {
        setUpRoster()
        val notices = notices()

        notices.announceChannelChange(2, 1, null, "Admin", false) { false }
        assertTrue("hints are off by default", systemTexts().isEmpty())

        notices.joinHintsEnabled = true
        notices.announceChannelChange(2, 1, null, "Admin", false) { false }
        assertEquals(
            listOf(strings.getString(R.string.chat_user_joined, "Admin", "Root")),
            systemTexts(),
        )

        // A remote join in a channel that is not mine stays silent.
        notices.announceChannelChange(2, 2, null, "Admin", false) { false }
        assertEquals(1, systemTexts().size)
    }

    @Test
    fun userRemoved_localBanIsFormattedWithActorAndReason() {
        setUpRoster()

        val event = notices().userRemoved(session = 1, actor = 2, hasActor = true, reason = "spam", ban = true)

        assertTrue(event.isLocal)
        assertEquals(ServerRemovalKind.BANNED, event.removal?.kind)
        val expected = strings.getString(R.string.you_were_kicked_banned, "Admin") +
            strings.getString(R.string.notice_reason_suffix, "spam")
        assertEquals(expected, event.message)
        assertEquals(listOf(expected), systemTexts())
    }

    @Test
    fun userRemoved_ordinaryLeaveIsNotAServerRemoval() {
        setUpRoster()

        val event = notices().userRemoved(session = 2, actor = 0, hasActor = false, reason = "", ban = false)

        assertNull(event.removal)
        assertNull(event.message)
        assertFalse(event.isLocal)
        assertTrue(systemTexts().isEmpty())
    }

    @Test
    fun permissionDeniedText_prefersTheServerReason() {
        val denied = PermissionDenied.newBuilder().setReason("nope").build()

        assertEquals("nope", notices().permissionDeniedText(denied))
    }

    @Test
    fun permissionDeniedText_mapsKnownDenyTypes() {
        val notices = notices()
        fun deny(type: PermissionDenied.DenyType) =
            PermissionDenied.newBuilder().setType(type).build()

        assertEquals(
            strings.getString(R.string.permission_denied_superuser),
            notices.permissionDeniedText(deny(PermissionDenied.DenyType.SuperUser)),
        )
        assertEquals(
            strings.getString(R.string.permission_denied_channel_full),
            notices.permissionDeniedText(deny(PermissionDenied.DenyType.ChannelFull)),
        )
        assertEquals(
            strings.getString(R.string.permission_denied_permission),
            notices.permissionDeniedText(deny(PermissionDenied.DenyType.Permission)),
        )
    }
}
