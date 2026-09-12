package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.ChannelState
import dev.woms.mumdroid.core.proto.ContextAction
import dev.woms.mumdroid.core.proto.ContextActionModify
import dev.woms.mumdroid.core.proto.PermissionQuery
import dev.woms.mumdroid.core.proto.Ping
import dev.woms.mumdroid.core.proto.QueryUsers
import dev.woms.mumdroid.core.proto.RequestBlob
import dev.woms.mumdroid.core.proto.SuggestConfig
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.UserList
import dev.woms.mumdroid.core.proto.UserRemove
import dev.woms.mumdroid.core.proto.UserState
import dev.woms.mumdroid.core.proto.UserStats
import dev.woms.mumdroid.core.proto.Version
import dev.woms.mumdroid.core.proto.VoiceTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun versionEncodeDecode() {
        val v = Version.newBuilder()
            .setVersionV1(0x010500)
            .setRelease("mumdroid")
            .setOs("Android")
            .setOsVersion("15")
            .build()
        val data = v.toByteArray()

        val decoded = Version.parseFrom(data)
        assertEquals(0x010500, decoded.versionV1)
        assertEquals("mumdroid", decoded.release)
        assertEquals("Android", decoded.os)
        assertEquals("15", decoded.osVersion)
    }

    @Test
    fun tcpMessageTypesMatchOfficialFraming() {
        assertEquals(0, dev.woms.mumdroid.core.net.MessageType.VERSION)
        assertEquals(1, dev.woms.mumdroid.core.net.MessageType.UDP_TUNNEL)
        assertEquals(25, dev.woms.mumdroid.core.net.MessageType.SUGGEST_CONFIG)
        assertEquals(26, dev.woms.mumdroid.core.net.MessageType.PLUGIN_DATA_TRANSMISSION)
    }

    @Test
    fun pluginDataTransmissionRoundTrip() {
        val msg = dev.woms.mumdroid.core.proto.PluginDataTransmission.newBuilder()
            .setSenderSession(3)
            .addReceiverSessions(7)
            .setData(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setDataID("test")
            .build()
        val decoded = dev.woms.mumdroid.core.proto.PluginDataTransmission.parseFrom(msg.toByteArray())
        assertEquals(3, decoded.senderSession)
        assertEquals(listOf(7), decoded.receiverSessionsList)
        assertEquals("test", decoded.dataID)
    }

    @Test
    fun textMessageRoundTrip() {
        val msg = TextMessage.newBuilder()
            .setActor(7)
            .addAllChannelId(listOf(1, 2, 3))
            .setMessage("hi")
            .build()
        val data = msg.toByteArray()

        val decoded = TextMessage.parseFrom(data)
        assertEquals(7, decoded.actor)
        assertEquals(listOf(1, 2, 3), decoded.channelIdList)
        assertEquals("hi", decoded.message)
    }

    @Test
    fun pingRoundTrip() {
        val ping = Ping.newBuilder()
            .setTimestamp(123456L)
            .setGood(10)
            .setLost(2)
            .build()
        val decoded = Ping.parseFrom(ping.toByteArray())
        assertEquals(123456L, decoded.timestamp)
        assertEquals(10, decoded.good)
        assertEquals(2, decoded.lost)
    }

    @Test
    fun banListRoundTrip() {
        val ban = BanList.BanEntry.newBuilder()
            .setAddress(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
            .setMask(24)
            .setName("bad user")
            .setHash("deadbeef")
            .setReason("spam")
            .setStart("2026-01-01")
            .setDuration(3600)
            .build()
        val msg = BanList.newBuilder().addBans(ban).setQuery(true).build()
        val decoded = BanList.parseFrom(msg.toByteArray())
        assertTrue(decoded.query)
        assertEquals(1, decoded.bansCount)
        val d = decoded.getBans(0)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), d.address.toByteArray())
        assertEquals(24, d.mask)
        assertEquals("bad user", d.name)
        assertEquals(3600, d.duration)
    }

    @Test
    fun userRemoveKickRoundTrip() {
        val msg = UserRemove.newBuilder()
            .setSession(7)
            .setActor(1)
            .setReason("spam")
            .setBan(true)
            .build()
        val decoded = UserRemove.parseFrom(msg.toByteArray())
        assertEquals(7, decoded.session)
        assertEquals(1, decoded.actor)
        assertTrue(decoded.hasActor())
        assertEquals("spam", decoded.reason)
        assertTrue(decoded.ban)
        assertEquals(1, UserRemove.SESSION_FIELD_NUMBER)
        assertEquals(2, UserRemove.ACTOR_FIELD_NUMBER)
        assertEquals(3, UserRemove.REASON_FIELD_NUMBER)
        assertEquals(4, UserRemove.BAN_FIELD_NUMBER)
        assertEquals(5, UserRemove.BAN_CERTIFICATE_FIELD_NUMBER)
        assertEquals(6, UserRemove.BAN_IP_FIELD_NUMBER)
    }

    @Test
    fun userRemoveBanOptionsRoundTrip() {
        val msg = UserRemove.newBuilder()
            .setSession(4)
            .setBan(true)
            .setBanCertificate(true)
            .setBanIp(false)
            .build()
        val decoded = UserRemove.parseFrom(msg.toByteArray())
        assertTrue(decoded.ban)
        assertTrue(decoded.banCertificate)
        assertFalse(decoded.banIp)
        assertTrue(decoded.hasBanCertificate())
        assertTrue(decoded.hasBanIp())
    }

    @Test
    fun aclRoundTrip() {
        val msg = ACL.newBuilder()
            .setChannelId(7)
            .setQuery(true)
            .setInheritAcls(false)
            .build()
        val decoded = ACL.parseFrom(msg.toByteArray())
        assertEquals(7, decoded.channelId)
        assertTrue(decoded.query)
        org.junit.Assert.assertFalse(decoded.inheritAcls)
    }

    @Test
    fun queryUsersRoundTrip() {
        val msg = QueryUsers.newBuilder().addIds(42).addNames("alice").build()
        val decoded = QueryUsers.parseFrom(msg.toByteArray())
        assertEquals(listOf(42), decoded.idsList)
        assertEquals(listOf("alice"), decoded.namesList)
    }

    @Test
    fun contextActionModifyRoundTrip() {
        val msg = ContextActionModify.newBuilder()
            .setAction("poke")
            .setText("Poke")
            .setContext(ContextActionModify.Context.User_VALUE or ContextActionModify.Context.Channel_VALUE)
            .setOperation(ContextActionModify.Operation.Add)
            .build()
        val decoded = ContextActionModify.parseFrom(msg.toByteArray())
        assertEquals("poke", decoded.action)
        assertEquals("Poke", decoded.text)
        assertEquals(ContextActionModify.Operation.Add, decoded.operation)
    }

    @Test
    fun contextActionRoundTrip() {
        val msg = ContextAction.newBuilder()
            .setSession(3)
            .setChannelId(9)
            .setAction("poke")
            .build()
        val decoded = ContextAction.parseFrom(msg.toByteArray())
        assertEquals(3, decoded.session)
        assertEquals(9, decoded.channelId)
        assertEquals("poke", decoded.action)
    }

    @Test
    fun userListRoundTrip() {
        val u = UserList.User.newBuilder()
            .setUserId(11)
            .setName("bob")
            .setLastSeen("2026-01-01")
            .setLastChannel(5)
            .build()
        val msg = UserList.newBuilder().addUsers(u).build()
        val decoded = UserList.parseFrom(msg.toByteArray())
        assertEquals(1, decoded.usersCount)
        val d = decoded.getUsers(0)
        assertEquals(11, d.userId)
        assertEquals("bob", d.name)
        assertEquals(5, d.lastChannel)
    }

    @Test
    fun userListUnregisterOmitsName() {
        val unregister = UserList.User.newBuilder().setUserId(11).build()
        assertFalse(unregister.hasName())
        val emptyName = UserList.User.newBuilder().setUserId(11).setName("").build()
        assertTrue(emptyName.hasName())
        val decoded = UserList.parseFrom(
            UserList.newBuilder().addUsers(unregister).build().toByteArray(),
        )
        assertEquals(11, decoded.getUsers(0).userId)
        assertFalse(decoded.getUsers(0).hasName())
    }

    @Test
    fun voiceTargetRoundTrip() {
        val t = VoiceTarget.Target.newBuilder().addSession(1).addSession(2).build()
        val msg = VoiceTarget.newBuilder().setId(3).addTargets(t).build()
        val decoded = VoiceTarget.parseFrom(msg.toByteArray())
        assertEquals(3, decoded.id)
        assertEquals(1, decoded.targetsCount)
        assertEquals(listOf(1, 2), decoded.getTargets(0).sessionList)
    }

    @Test
    fun permissionQueryRoundTrip() {
        val msg = PermissionQuery.newBuilder()
            .setChannelId(4)
            .setPermissions(0x1f)
            .setFlush(true)
            .build()
        val decoded = PermissionQuery.parseFrom(msg.toByteArray())
        assertEquals(4, decoded.channelId)
        assertEquals(0x1f, decoded.permissions)
        assertTrue(decoded.flush)
    }

    @Test
    fun userStatsRoundTrip() {
        val stats = UserStats.Stats.newBuilder().setGood(100).setLost(3).build()
        val msg = UserStats.newBuilder()
            .setSession(9)
            .setFromClient(stats)
            .setBandwidth(48000)
            .setOnlinesecs(120)
            .setTcpPingAvg(25.5f)
            .build()
        val decoded = UserStats.parseFrom(msg.toByteArray())
        assertEquals(9, decoded.session)
        assertEquals(48000, decoded.bandwidth)
        assertEquals(120, decoded.onlinesecs)
        assertEquals(25.5f, decoded.tcpPingAvg, 0.01f)
        assertEquals(100, decoded.fromClient.good)
    }

    @Test
    fun requestBlobRoundTrip() {
        val msg = RequestBlob.newBuilder()
            .addSessionTexture(1)
            .addSessionComment(2)
            .addChannelDescription(3)
            .build()
        val decoded = RequestBlob.parseFrom(msg.toByteArray())
        assertEquals(listOf(1), decoded.sessionTextureList)
        assertEquals(listOf(2), decoded.sessionCommentList)
        assertEquals(listOf(3), decoded.channelDescriptionList)
    }

    @Test
    fun suggestConfigRoundTrip() {
        val msg = SuggestConfig.newBuilder()
            .setPositional(false)
            .setPushToTalk(true)
            .build()
        val decoded = SuggestConfig.parseFrom(msg.toByteArray())
        assertEquals(false, decoded.positional)
        assertTrue(decoded.pushToTalk)
    }

    /**
     * The official protobuf runtime must skip unknown fields (e.g. newer
     * protocol fields such as ChannelState.description_hash) without
     * desynchronising the rest of the message. This guards against the
     * "秒退出" (immediate disconnect) bug that plagued the hand-rolled decoder.
     */
    @Test
    fun channelStateDecode_skipsUnknownField() {
        val builder = ChannelState.newBuilder()
            .setChannelId(42)
            .setName("Root")
            .setTemporary(true)
            .setMaxUsers(128)
            .setCanEnter(true)
            .build()
        val base = builder.toByteArray()

        // Simulate a server that also sends the description_hash field (10)
        // by merging an extra unknown field into the encoded bytes.
        val data = mergeUnknownBytesField(base, 10, ByteArray(24) { it.toByte() })

        val cs = ChannelState.parseFrom(data)
        assertEquals(42, cs.channelId)
        assertEquals("Root", cs.name)
        assertTrue(cs.temporary)
        assertEquals(128, cs.maxUsers)
        assertTrue(cs.canEnter)
    }

    @Test
    fun userState_officialFieldNumbers() {
        // Official Mumble.proto: 11=texture (bytes), 15=hash (string), 19=recording.
        val us = UserState.newBuilder()
            .setSession(3)
            .setHash("abcdef")
            .setRecording(true)
            .setTexture(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()
        val decoded = UserState.parseFrom(us.toByteArray())
        assertEquals("abcdef", decoded.hash)
        assertTrue(decoded.recording)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.texture.toByteArray())
        assertEquals(15, UserState.HASH_FIELD_NUMBER)
        assertEquals(11, UserState.TEXTURE_FIELD_NUMBER)
        assertEquals(18, UserState.PRIORITY_SPEAKER_FIELD_NUMBER)
        assertEquals(19, UserState.RECORDING_FIELD_NUMBER)
        assertEquals(20, UserState.TEMPORARY_ACCESS_TOKENS_FIELD_NUMBER)
        assertEquals(21, UserState.LISTENING_CHANNEL_ADD_FIELD_NUMBER)
        assertEquals(22, UserState.LISTENING_CHANNEL_REMOVE_FIELD_NUMBER)
        assertEquals(1, dev.woms.mumdroid.core.proto.ChannelRemove.CHANNEL_ID_FIELD_NUMBER)
        assertEquals(2, ChannelState.PARENT_FIELD_NUMBER)
        assertEquals(9, ChannelState.POSITION_FIELD_NUMBER)
        assertEquals(11, ChannelState.MAX_USERS_FIELD_NUMBER)
        assertEquals(2, ACL.INHERIT_ACLS_FIELD_NUMBER)
        assertEquals(5, ACL.QUERY_FIELD_NUMBER)
    }

    @Test
    fun userStateDecode_skipsUnknownFixedAndVarintFields() {
        val us = UserState.newBuilder()
            .setSession(7)
            .setName("alice")
            .setChannelId(3)
            .setSelfMute(true)
            .build()
        val base = us.toByteArray()

        // Insert unused field numbers so the skip test does not collide with
        // official UserState fields (11 = texture, 12 = plugin_context).
        val withFixed = prependUnknownFixed32(base, 30, 0.5f)
        val data = prependUnknownVarint(withFixed, 31, 999)

        val decoded = UserState.parseFrom(data)
        assertEquals(7, decoded.session)
        assertEquals("alice", decoded.name)
        assertEquals(3, decoded.channelId)
        assertTrue(decoded.selfMute)
    }

    // ---- Helpers to simulate unknown fields the runtime must skip ----

    /** Merges an extra length-delimited field into an existing encoded message. */
    private fun mergeUnknownBytesField(base: ByteArray, fieldNumber: Int, value: ByteArray): ByteArray {
        val tag = varint(((fieldNumber shl 3) or 2).toLong())
        val size = varint(value.size.toLong())
        return tag + size + value + base
    }

    /** Prepends an unknown fixed32 field. */
    private fun prependUnknownFixed32(base: ByteArray, fieldNumber: Int, value: Float): ByteArray {
        val tag = varint(((fieldNumber shl 3) or 5).toLong())
        val bits = value.toRawBits()
        val le = byteArrayOf(
            (bits and 0xff).toByte(),
            ((bits ushr 8) and 0xff).toByte(),
            ((bits ushr 16) and 0xff).toByte(),
            ((bits ushr 24) and 0xff).toByte(),
        )
        return tag + le + base
    }

    /** Prepends an unknown varint field. */
    private fun prependUnknownVarint(base: ByteArray, fieldNumber: Int, value: Long): ByteArray {
        val tag = varint(((fieldNumber shl 3) or 0).toLong())
        return tag + varint(value) + base
    }

    private fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (v and -0x80L != 0L) {
            out.add(((v and 0x7fL) or 0x80L).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }
}
