package dev.woms.mumdroid

import com.google.protobuf.MessageLite
import dev.woms.mumdroid.core.net.MessageType
import dev.woms.mumdroid.core.net.MumbleControlSenders
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.QueryUsers
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.UserList
import dev.woms.mumdroid.core.proto.UserRemove
import dev.woms.mumdroid.core.proto.UserState
import dev.woms.mumdroid.core.proto.UserStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captures what the extracted typed sender surface (official `ServerHandler`)
 * actually puts on the wire, without a socket or a live TLS session.
 */
class MumbleControlSendersTest {

    /** Records the writes and the session/token state the host exposes. */
    private class RecordingHost : MumbleControlSenders.Host {
        val messages = mutableListOf<Pair<Int, MessageLite>>()
        val rawWrites = mutableListOf<Pair<Int, ByteArray>>()
        var session = 12
        val tokens = mutableListOf("old")

        override fun writeMessage(type: Int, message: MessageLite) {
            messages.add(type to message)
        }

        override fun writeBytes(type: Int, body: ByteArray) {
            rawWrites.add(type to body)
        }

        override fun localSession(): Int = session

        override fun accessTokens(): MutableList<String> = tokens
    }

    private val hostValue = RecordingHost()
    private val senders = MumbleControlSenders().apply { attach(hostValue) }

    private val lastMessage: MessageLite get() = hostValue.messages.last().second
    private val lastType: Int get() = hostValue.messages.last().first

    @Test
    fun sendTextToChannel_targetsChannel() {
        senders.sendTextToChannel(4, "hello")
        assertEquals(MessageType.TEXT_MESSAGE, lastType)
        val msg = lastMessage as TextMessage
        assertEquals("hello", msg.message)
        assertEquals(listOf(4), msg.channelIdList)
        assertTrue(msg.sessionList.isEmpty())
    }

    @Test
    fun sendTextToUser_targetsSession() {
        senders.sendTextToUser(9, "psst")
        val msg = lastMessage as TextMessage
        assertEquals(listOf(9), msg.sessionList)
        assertTrue(msg.channelIdList.isEmpty())
    }

    @Test
    fun kickUser_sendsUserRemoveWithoutBan() {
        senders.kickUser(3, "spam")
        assertEquals(MessageType.USER_REMOVE, lastType)
        val msg = lastMessage as UserRemove
        assertEquals(3, msg.session)
        assertFalse(msg.ban)
    }

    @Test
    fun joinChannel_usesLocalSessionAndTemporaryTokens() {
        senders.joinChannel(7, listOf("pw"))
        assertEquals(MessageType.USER_STATE, lastType)
        val msg = lastMessage as UserState
        assertEquals(12, msg.session)
        assertEquals(7, msg.channelId)
        assertEquals(listOf("pw"), msg.temporaryAccessTokensList)
    }

    @Test
    fun setChannelListening_targetsLocalSession() {
        senders.setChannelListening(channelId = 5, listen = true)
        val msg = lastMessage as UserState
        assertEquals(12, msg.session)
        assertEquals(5, msg.listeningChannelAddList.single())
        assertTrue(msg.listeningChannelRemoveList.isEmpty())
    }

    @Test
    fun setTokens_replacesHostTokensAndSendsAuthenticate() {
        senders.setTokens(listOf("a", "b"))
        assertEquals(MessageType.AUTHENTICATE, lastType)
        assertEquals(listOf("a", "b"), hostValue.tokens)
    }

    @Test
    fun requestUserStats_defaultsToFullStats() {
        senders.requestUserStats(2, statsOnly = false)
        val msg = lastMessage as UserStats
        assertEquals(2, msg.session)
        assertFalse(msg.statsOnly)
    }

    @Test
    fun requestAcl_setsQueryFlag() {
        senders.requestAcl(6)
        assertEquals(MessageType.ACL, lastType)
        assertTrue((lastMessage as ACL).query)
    }

    @Test
    fun queryUsers_dropsUnregisteredUserId() {
        // Official `UserId.UNREGISTERED` is -1 and may not be queried.
        senders.queryUsers(ids = listOf(-1, 5), names = listOf("bob"))
        val msg = lastMessage as QueryUsers
        assertEquals(listOf(5), msg.idsList)
        assertEquals(listOf("bob"), msg.namesList)
    }

    @Test
    fun sendUserList_omitsEmptyName() {
        senders.sendUserList(
            listOf(
                dev.woms.mumdroid.core.model.RegisteredUser(1, "alice", "", 0),
                dev.woms.mumdroid.core.model.RegisteredUser(2, "", "", 0),
            ),
        )
        val msg = lastMessage as UserList
        assertTrue(msg.usersList[0].hasName())
        assertFalse(msg.usersList[1].hasName())
    }

    @Test
    fun requestBanList_setsQueryFlag() {
        senders.requestBanList()
        assertEquals(MessageType.BAN_LIST, lastType)
        assertTrue((lastMessage as BanList).query)
    }

    @Test
    fun sendTunneledVoice_writesRawBodyUnderUdpTunnel() {
        senders.sendTunneledVoice(byteArrayOf(1, 2, 3))
        val (type, body) = hostValue.rawWrites.single()
        assertEquals(MessageType.UDP_TUNNEL, type)
        assertEquals(listOf<Byte>(1, 2, 3), body.toList())
    }

    @Test
    fun queryPermissions_targetsChannel() {
        senders.queryPermissions(8)
        assertEquals(MessageType.PERMISSION_QUERY, lastType)
        assertEquals(8, (lastMessage as dev.woms.mumdroid.core.proto.PermissionQuery).channelId)
    }

    @Test
    fun resetUserComment_sendsEmptyComment() {
        senders.resetUserComment(4)
        val msg = lastMessage as UserState
        assertEquals(4, msg.session)
        assertEquals("", msg.comment)
        assertTrue(msg.hasComment())
    }
}
