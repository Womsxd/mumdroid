package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.MessageType
import dev.woms.mumdroid.core.net.MumbleListener
import dev.woms.mumdroid.core.net.MumbleMessageRouter
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.Ping
import dev.woms.mumdroid.core.proto.ServerSync
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.Version
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MumbleMessageRouterTest {

    /** Captures the fan-outs under test; everything else is a no-op. */
    private class RecordingListener : MumbleListener {
        val connected = mutableListOf<Int>()
        val versions = mutableListOf<Pair<Long, Int>>()
        val texts = mutableListOf<Pair<String, Boolean>>()
        val tunneled = mutableListOf<ByteArray>()
        val permissionQueries = mutableListOf<Triple<Int, Long, Boolean>>()
        val bans = mutableListOf<Pair<List<BanEntry>, Boolean>>()

        override fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int) {
            connected.add(session)
        }

        override fun onRejected(reason: String, type: Int) {}

        override fun onDisconnected(reason: String) {}

        override fun onChannelState(channel: Channel) {}

        override fun onChannelRemoved(channelId: Int) {}

        override fun onUserState(user: dev.woms.mumdroid.core.proto.UserState) {}

        override fun onUserRemoved(
            session: Int,
            actor: Int,
            hasActor: Boolean,
            reason: String,
            ban: Boolean,
        ) {}

        override fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean) {
            texts.add(actor to isPrivate)
        }

        override fun onInfo(message: String) {}

        override fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {}

        override fun onServerVersion(versionV2: Long, legacyVersion: Int) {
            versions.add(versionV2 to legacyVersion)
        }

        override fun onTunneledPacket(body: ByteArray) {
            tunneled.add(body)
        }

        override fun onBanList(bans: List<BanEntry>, query: Boolean) {
            this.bans.add(bans to query)
        }

        override fun onPermissionQuery(channelId: Int, permissions: Long, flush: Boolean) {
            permissionQueries.add(Triple(channelId, permissions, flush))
        }
    }

    private class RecordingHost : MumbleMessageRouter.Host {
        val versions = mutableListOf<Array<Any>>()
        val syncSessions = mutableListOf<Int>()
        val pings = mutableListOf<Array<Any>>()

        override fun onServerVersionMessage(
            versionV2: Long,
            versionLegacy: Int,
            release: String,
            os: String,
            osVersion: String,
        ) {
            versions.add(arrayOf(versionV2, versionLegacy, release, os, osVersion))
        }

        override fun onServerSync(session: Int) {
            syncSessions.add(session)
        }

        override fun onPing(timestampMs: Long, good: Int, late: Int, lost: Int, resync: Int) {
            pings.add(arrayOf(timestampMs, good, late, lost, resync))
        }
    }

    private val listener = RecordingListener()
    private val host = RecordingHost()
    private val ignored = mutableListOf<Int>()
    private val router = MumbleMessageRouter(
        listener = listener,
        host = host,
        onIgnored = { type, _ -> ignored.add(type) },
    )

    @Test
    fun versionMessage_reportsStateAndListener() {
        val body = Version.newBuilder()
            .setVersionV1(0x010500)
            .setVersionV2(0x0001000500000000L)
            .setRelease("murmur 1.5.0")
            .setOs("Linux")
            .setOsVersion("15")
            .build()
            .toByteArray()

        router.dispatch(MessageType.VERSION, body)

        val reported = host.versions.single()
        assertEquals(0x0001000500000000L, reported[0])
        assertEquals(0x010500, reported[1])
        assertEquals("murmur 1.5.0", reported[2])
        assertEquals("Linux", reported[3])
        assertEquals("15", reported[4])
        assertEquals(listOf(0x0001000500000000L to 0x010500), listener.versions)
    }

    @Test
    fun serverSync_setsSessionAndGreetsWithoutPermissionQuery() {
        val body = ServerSync.newBuilder()
            .setSession(7)
            .setWelcomeText("welcome")
            .setMaxBandwidth(558000)
            .build()
            .toByteArray()

        router.dispatch(MessageType.SERVER_SYNC, body)

        assertEquals(listOf(7), host.syncSessions)
        assertEquals(listOf(7), listener.connected)
        assertTrue(listener.permissionQueries.isEmpty())
    }

    @Test
    fun serverSync_withPermissions_reportsPermissionQuery() {
        val body = ServerSync.newBuilder().setSession(7).setPermissions(0x1).build().toByteArray()

        router.dispatch(MessageType.SERVER_SYNC, body)

        assertEquals(listOf(7), listener.connected)
        assertEquals(
            listOf(Triple(0, ChanACL.fromWire(0x1), false)),
            listener.permissionQueries,
        )
    }

    @Test
    fun ping_forwardedToHostUntouched() {
        val body = Ping.newBuilder()
            .setTimestamp(123456L)
            .setGood(5)
            .setLate(1)
            .setLost(2)
            .setResync(3)
            .build()
            .toByteArray()

        router.dispatch(MessageType.PING, body)

        val ping = host.pings.single()
        assertEquals(123456L, ping[0])
        assertEquals(5, ping[1])
        assertEquals(1, ping[2])
        assertEquals(2, ping[3])
        assertEquals(3, ping[4])
    }

    @Test
    fun textMessage_privateOnlyForSessionTargets() {
        val direct = TextMessage.newBuilder().setActor(3).addSession(9).setMessage("hi").build()
        val broadcast = TextMessage.newBuilder().setActor(3).addChannelId(1).setMessage("yo").build()

        router.dispatch(MessageType.TEXT_MESSAGE, direct.toByteArray())
        router.dispatch(MessageType.TEXT_MESSAGE, broadcast.toByteArray())

        assertEquals(listOf("3" to true, "3" to false), listener.texts)
    }

    @Test
    fun tunneledPacket_emptyBodyIgnoredNonEmptyDelivered() {
        router.dispatch(MessageType.UDP_TUNNEL, ByteArray(0))
        assertTrue(listener.tunneled.isEmpty())

        val body = byteArrayOf(0x80, 1, 2, 3)
        router.dispatch(MessageType.UDP_TUNNEL, body)
        assertEquals(1, listener.tunneled.size)
        assertArrayEquals(body, listener.tunneled[0])
    }

    @Test
    fun banList_mapsProtoEntries() {
        val body = BanList.newBuilder()
            .setQuery(true)
            .addBans(
                BanList.BanEntry.newBuilder()
                    .setAddress(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
                    .setMask(24)
                    .setName("bad user")
                    .setHash("deadbeef")
                    .setReason("spam")
                    .setStart("2026-01-01")
                    .setDuration(3600),
            )
            .build()
            .toByteArray()

        router.dispatch(MessageType.BAN_LIST, body)

        val (bans, query) = listener.bans.single()
        assertTrue(query)
        assertEquals(1, bans.size)
        assertEquals(24, bans[0].mask)
        assertEquals("bad user", bans[0].name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bans[0].address)
    }

    @Test
    fun unhandledAndPluginData_areReportedAsIgnored() {
        router.dispatch(999, byteArrayOf(1))
        router.dispatch(MessageType.PLUGIN_DATA_TRANSMISSION, byteArrayOf(1, 2))

        assertEquals(listOf(999, MessageType.PLUGIN_DATA_TRANSMISSION), ignored)
        assertTrue(listener.texts.isEmpty())
    }
}
