package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.crypto.CryptOCB2
import dev.woms.mumdroid.core.crypto.CryptState
import dev.woms.mumdroid.core.net.UdpVoiceManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun ocb2RoundTrip() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())

        val payload = ByteArray(100) { it.toByte() }
        val encrypted = ByteArray(payload.size)
        val tag = ByteArray(16)
        val written = crypt.encrypt(encrypted, payload, tag)
        assertTrue(written > 0)

        val decrypted = ByteArray(encrypted.size)
        val decWritten = crypt.decrypt(decrypted, encrypted, tag)
        assertTrue(decWritten > 0)
        assertArrayEquals(payload, decrypted)

        // Tampering with the tag must fail authentication.
        tag[0] = (tag[0].toInt() xor 1).toByte()
        val bad = crypt.decrypt(ByteArray(encrypted.size), encrypted, tag)
        assertEquals(-1, bad)
    }

    @Test
    fun ocb2AcceptsThreeByteWireTag() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())
        val payload = ByteArray(40) { it.toByte() }
        val encrypted = ByteArray(payload.size)
        val fullTag = ByteArray(16)
        assertTrue(crypt.encrypt(encrypted, payload, fullTag) > 0)
        val wireTag = byteArrayOf(fullTag[0], fullTag[1], fullTag[2])
        val decrypted = ByteArray(payload.size)
        assertTrue(crypt.decrypt(decrypted, encrypted, wireTag) > 0)
        assertArrayEquals(payload, decrypted)
        wireTag[0] = (wireTag[0].toInt() xor 1).toByte()
        assertEquals(-1, crypt.decrypt(ByteArray(payload.size), encrypted, wireTag))
    }

    @Test
    fun cryptStateLegacyRoundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16)
        val serverNonce = ByteArray(16)
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))

        // Encrypt a payload, then decrypt it with a mirroring state.
        val payload = ByteArray(50) { it.toByte() }
        val packet = crypt.encrypt(payload)
        assertNotNull(packet)
        assertEquals(4 + payload.size, packet!!.size)

        val mirror = CryptState()
        mirror.setKey(key, clientNonce, serverNonce)
        val decrypted = mirror.decrypt(packet)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)

        // Corrupting the tag must cause authentication failure.
        val badPacket = packet.copyOf()
        badPacket[1] = (badPacket[1].toInt() xor 1).toByte()
        assertNull(mirror.decrypt(badPacket))
    }

    @Test
    fun cryptStateDecrypt_readsFromOffsetInLargerBuffer() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16)
        val serverNonce = ByteArray(16)
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))

        val payload = ByteArray(50) { it.toByte() }
        val inner = ByteArray(7 + payload.size + 3)
        System.arraycopy(payload, 0, inner, 7, payload.size)
        val packet = crypt.encrypt(inner, 7, payload.size)
        assertNotNull(packet)
        assertEquals(4 + payload.size, packet!!.size)

        val padded = ByteArray(12 + packet.size + 9)
        System.arraycopy(packet, 0, padded, 12, packet.size)
        val mirror = CryptState()
        mirror.setKey(key, clientNonce, serverNonce)
        val decrypted = mirror.decrypt(padded, 12, packet.size)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun ocb2RoundTrip_usesBufferOffsets() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())

        val payload = ByteArray(100) { it.toByte() }
        val inputPadded = ByteArray(8 + payload.size + 3)
        System.arraycopy(payload, 0, inputPadded, 8, payload.size)
        val outputPadded = ByteArray(4 + payload.size + 5)
        val tag = ByteArray(16)
        val written = crypt.encrypt(
            outputPadded, inputPadded, tag,
            inputOffset = 8,
            inputLength = payload.size,
            outputOffset = 4,
        )
        assertEquals(payload.size, written)

        val decrypted = ByteArray(payload.size)
        val decWritten = crypt.decrypt(
            decrypted, outputPadded, tag,
            inputOffset = 4,
            inputLength = payload.size,
        )
        assertEquals(payload.size, decWritten)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun cryptState_resyncCountsNonceNotFullKey() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16) { 1 }
        val serverNonce = ByteArray(16) { 2 }
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))
        assertEquals(0, crypt.resyncPackets)
        // Full key re-delivery is not a nonce resync (official setKey).
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))
        assertEquals(0, crypt.resyncPackets)
        // Official CryptStateOCB2::setDecryptIV only replaces the IV.
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 3 }))
        assertEquals(0, crypt.resyncPackets)
        // Official msgCryptSetup: m_statsLocal.resync++ then setDecryptIV.
        crypt.incrementResync()
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 4 }))
        assertEquals(1, crypt.resyncPackets)
        crypt.incrementResync()
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 5 }))
        assertEquals(2, crypt.resyncPackets)
        assertFalse(crypt.setDecryptIV(ByteArray(8)))
        assertEquals(2, crypt.resyncPackets)
        val udp = UdpVoiceManager("127.0.0.1", 64738)
        udp.setupCryptography(key, clientNonce, serverNonce)
        assertEquals(0, udp.packetStats().resync)
        assertTrue(udp.resyncDecryptIV(ByteArray(16) { 6 }))
        assertEquals(1, udp.packetStats().resync)
        assertFalse(udp.resyncDecryptIV(ByteArray(8)))
        assertEquals(1, udp.packetStats().resync)
        udp.close()
    }

    @Test
    fun cryptState_concurrentEncryptDecrypt() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val clientIv = ByteArray(16) { 1 }
        val serverIv = ByteArray(16) { 2 }
        val local = CryptState()
        val remote = CryptState()
        assertTrue(local.setKey(key, clientIv, serverIv))
        assertTrue(remote.setKey(key, serverIv, clientIv))

        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val rounds = 200
        val send = Thread {
            repeat(rounds) { i ->
                val payload = ByteArray(40) { (i + it).toByte() }
                val packet = local.encrypt(payload)
                if (packet == null) {
                    errors.add("local encrypt failed at $i")
                    return@repeat
                }
                val plain = remote.decrypt(packet)
                if (plain == null || !plain.contentEquals(payload)) {
                    errors.add("remote decrypt failed at $i")
                }
            }
        }
        val recv = Thread {
            repeat(rounds) { i ->
                val payload = ByteArray(36) { (i * 2 + it).toByte() }
                val packet = remote.encrypt(payload)
                if (packet == null) {
                    errors.add("remote encrypt failed at $i")
                    return@repeat
                }
                val plain = local.decrypt(packet)
                if (plain == null || !plain.contentEquals(payload)) {
                    errors.add("local decrypt failed at $i")
                }
            }
        }
        send.start()
        recv.start()
        send.join()
        recv.join()
        assertTrue(errors.joinToString(), errors.isEmpty())
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

    // ---- UdpPacketCodec (legacy UDP framing) ----

    @Test
    fun udpVarintRoundTrip() {
        val values = longArrayOf(
            0, 1, 127, 128, 300, 8191, 8192, 0x1FFF,
            Int.MAX_VALUE.toLong(), Long.MAX_VALUE,
            -1, -2, -3, -4, -5, -6, -127, -128, -129,
            -(1L shl 31), -(1L shl 32), -(1L shl 32) - 1, -(1L shl 40), Long.MIN_VALUE,
        )
        for (value in values) {
            val out = java.io.ByteArrayOutputStream()
            dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(value, out)
            val bytes = out.toByteArray()
            val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(bytes, 0, bytes.size)
            assertNotNull(parsed)
            assertEquals(value, parsed!!.first)
            assertEquals(bytes.size, parsed.second)
        }
    }

    @Test
    fun udpVarintSignedEncodingMatchesOfficial() {
        fun bytes(value: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(value, out)
            return out.toByteArray()
        }
        fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()

        // ~i then compact / 0xFC, not absolute value (so -5 is F8 04, not F8 05).
        assertArrayEquals(b(0xFC), bytes(-1))
        assertArrayEquals(b(0xFD), bytes(-2))
        assertArrayEquals(b(0xFE), bytes(-3))
        assertArrayEquals(b(0xFF), bytes(-4))
        assertArrayEquals(b(0xF8, 0x04), bytes(-5))
        assertArrayEquals(b(0xF8, 0x05), bytes(-6))
        assertArrayEquals(b(0xF8, 0x7E), bytes(-127))
        assertArrayEquals(b(0xF8, 0x80, 0x80), bytes(-129))
        // Upper end of the official signed window: -2^32 still uses 0xF8.
        assertArrayEquals(
            b(0xF8, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
            bytes(-(1L shl 32)),
        )
        // Below -2^32 the official encoder skips 0xF8 and writes 0xF4.
        assertArrayEquals(
            b(0xF4, 0xFF, 0xFF, 0xFF, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF),
            bytes(-(1L shl 32) - 1),
        )
        assertArrayEquals(
            b(0xF4, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(Long.MIN_VALUE),
        )
    }

    @Test
    fun udpVarintRejectsDeepF8Nesting() {
        fun nestedF8(count: Int): ByteArray = ByteArray(count + 1) { i ->
            if (i < count) 0xF8.toByte() else 0x02
        }
        // Official decode_next_int allows 8 nested 0xF8 prefixes (levels 0..7).
        val ok = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(nestedF8(8), 0, 9)
        assertNotNull(ok)
        assertEquals(2L, ok!!.first)
        assertEquals(9, ok.second)
        // A 9th 0xF8 hits recursionLevel >= 8 and is rejected.
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(nestedF8(9), 0, 10))
    }

    @Test
    fun udpLegacyOpusFrameParsing() {
        // Build a server->client legacy Opus packet:
        // [header (type=4<<5 | context 0)][senderSession varint][frameNumber varint][size varint][opus]
        val session = 42
        val frameNumber = 7L
        val opusPayload = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val out = java.io.ByteArrayOutputStream()
        out.write((4 shl 5) and 0xff)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(session.toLong(), out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(frameNumber, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(opusPayload.size.toLong(), out)
        out.write(opusPayload)
        val packet = out.toByteArray()

        val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(packet)
        assertNotNull(parsed)
        assertEquals(session, parsed!!.first)
        assertArrayEquals(opusPayload, parsed.second)
    }

    @Test
    fun udpLegacyOpusRejectsWrongTypeAndTruncated() {
        // Wrong type (CELT_alpha = 0) should be rejected.
        val bad = byteArrayOf(0, 0, 1)
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(bad))
        // Truncated (size claims more data than present) should be rejected.
        val truncated = byteArrayOf((4 shl 5).toByte(), 5, 1, 0x50)
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(truncated))
    }

    @Test
    fun udpLegacyOpusEncode_stampsFrameAndLastFlag() {
        val payload = byteArrayOf(1, 2, 3)
        val packet = dev.woms.mumdroid.core.net.UdpPacketCodec.encodeLegacyOpus(
            payload,
            isLastFrame = true,
            frameNumber = 6L,
        )
        assertEquals(
            dev.woms.mumdroid.core.net.UdpPacketCodec.talkHeaderByte(),
            packet[0],
        )
        val frame = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, 1, packet.size)
        assertNotNull(frame)
        assertEquals(6L, frame!!.first)
        val size = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, frame.second, packet.size)
        assertNotNull(size)
        assertEquals(0x2000L or 3L, size!!.first)
        assertArrayEquals(payload, packet.copyOfRange(size.second, packet.size))
    }

    @Test
    fun udpLegacyPingEncodeRoundTrip() {
        val ts = 0x1122334455667788L
        val packet = dev.woms.mumdroid.core.net.UdpPacketCodec.encodePing(ts)
        assertEquals(
            dev.woms.mumdroid.core.net.UdpType.PING,
            (packet[0].toInt() ushr 5) and 0x07,
        )
        assertEquals(ts, dev.woms.mumdroid.core.net.UdpPacketCodec.readPingTimestamp(packet))
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.readPingTimestamp(byteArrayOf(0x20)))
    }

    // ---- ProtoUdpCodec (new Mumble >= 1.5.0 protobuf UDP framing) ----

    @Test
    fun protoUdpAudioRoundTrip() {
        val opusPayload = byteArrayOf(0x0a, 0x0b, 0x0c)
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodeAudio(
            frameNumber = 1234L,
            opusData = opusPayload,
            target = 0,
        )
        assertEquals(0, packet[0].toInt()) // Audio header byte
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet.copyOfRange(1, packet.size))
        assertNotNull(decoded)
    }

    @Test
    fun protoUdpAudioDecodeFields() {
        val opusPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodeAudio(77L, opusPayload, target = 0)
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet.copyOfRange(1, packet.size))
        assertNotNull(decoded)
        assertArrayEquals(opusPayload, decoded!!.payload)
        org.junit.Assert.assertFalse(decoded.isLastFrame)
        val viaOffset = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet, 1, packet.size - 1)
        assertNotNull(viaOffset)
        assertArrayEquals(opusPayload, viaOffset!!.payload)
    }

    @Test
    fun protoUdpServerAudioDecode() {
        val audio = dev.woms.mumdroid.core.udpproto.Audio.newBuilder()
            .setContext(2)
            .setSenderSession(42)
            .setFrameNumber(9L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(byteArrayOf(9, 8, 7)))
            .setIsTerminator(true)
            .build()
        val body = audio.toByteArray()
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(body)
        assertNotNull(decoded)
        assertEquals(42, decoded!!.session)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.payload)
        assertTrue(decoded.isLastFrame)
    }

    @Test
    fun protoUdpPingRoundTrip() {
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodePing(987654321L)
        assertEquals(1, packet[0].toInt()) // Ping header byte
        val ts = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodePing(packet.copyOfRange(1, packet.size))
        assertNotNull(ts)
        assertEquals(987654321L, ts!!.toLong())
    }

    @Test
    fun protoUdpDecodeRejectsInvalid() {
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(ByteArray(0)))
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodePing(ByteArray(0)))
        // Audio message without opus data is invalid per the official decoder.
        val empty = dev.woms.mumdroid.core.udpproto.Audio.newBuilder().setTarget(0).build().toByteArray()
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(empty))
    }

    @Test
    fun outgoingVoice_stampsTenMsFrameNumbers() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.framesPerPacket = 2
        val p0 = udp.buildTunnelPacket(byteArrayOf(1, 2, 3), false, 2)
        val p1 = udp.buildTunnelPacket(byteArrayOf(4, 5, 6), false, 2)
        val p2 = udp.buildTunnelPacket(byteArrayOf(7, 8, 9), false, 2)
        fun frameNumber(packet: ByteArray): Long {
            val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, 1, packet.size)
            return parsed!!.first
        }
        assertEquals(0L, frameNumber(p0))
        assertEquals(2L, frameNumber(p1))
        assertEquals(4L, frameNumber(p2))
        udp.close()
    }

    @Test
    fun protoOutgoingVoice_stampsTenMsFrameNumbers() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.protobufMode = true
        udp.framesPerPacket = 2
        val p0 = udp.buildTunnelPacket(byteArrayOf(1, 2, 3), false, 2)
        val p1 = udp.buildTunnelPacket(byteArrayOf(4, 5, 6), false, 2)
        assertEquals(0, p0[0].toInt())
        val d0 = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(p0.copyOfRange(1, p0.size))
        val d1 = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(p1.copyOfRange(1, p1.size))
        assertEquals(0L, d0!!.frameNumber)
        assertEquals(2L, d1!!.frameNumber)
        udp.close()
    }

    @Test
    fun playTunneled_protobufDispatchesAudio() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.protobufMode = true
        var session = -1
        var frame = -1L
        var payload: ByteArray? = null
        udp.setListener(object : dev.woms.mumdroid.core.net.UdpVoiceManager.Listener {
            override fun onAudioPacket(
                sessionId: Int,
                frameNumber: Long,
                opus: ByteArray,
                isLastFrame: Boolean,
            ) {
                session = sessionId
                frame = frameNumber
                payload = opus
            }
            override fun onUdpPing(rttMillis: Long) {}
            override fun onUdpConnected() {}
            override fun onUdpError(message: String) {}
        })
        val opusPayload = byteArrayOf(9, 8, 7, 6)
        val audio = dev.woms.mumdroid.core.udpproto.Audio.newBuilder()
            .setContext(0)
            .setSenderSession(42)
            .setFrameNumber(10L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(opusPayload))
            .build()
        val proto = audio.toByteArray()
        val body = ByteArray(1 + proto.size)
        body[0] = 0
        System.arraycopy(proto, 0, body, 1, proto.size)
        udp.playTunneled(body)
        assertEquals(42, session)
        assertEquals(10L, frame)
        assertArrayEquals(opusPayload, payload)
        udp.close()
    }

    @Test
    fun playTunneled_legacyDispatchesAudio() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        var session = -1
        var payload: ByteArray? = null
        udp.setListener(object : dev.woms.mumdroid.core.net.UdpVoiceManager.Listener {
            override fun onAudioPacket(
                sessionId: Int,
                frameNumber: Long,
                opus: ByteArray,
                isLastFrame: Boolean,
            ) {
                session = sessionId
                payload = opus
            }
            override fun onUdpPing(rttMillis: Long) {}
            override fun onUdpConnected() {}
            override fun onUdpError(message: String) {}
        })
        val opusPayload = byteArrayOf(1, 2, 3, 4, 5)
        val out = java.io.ByteArrayOutputStream()
        out.write((dev.woms.mumdroid.core.net.UdpType.VOICE_OPUS shl 5) or 0)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(7, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(4, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(opusPayload.size.toLong(), out)
        out.write(opusPayload)
        udp.playTunneled(out.toByteArray())
        assertEquals(7, session)
        assertArrayEquals(opusPayload, payload)
        udp.close()
    }
}
