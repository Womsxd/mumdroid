package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.net.ProtoUdpCodec
import dev.woms.mumdroid.core.net.ServerPingCodec
import dev.woms.mumdroid.core.net.ServerPingPacket
import dev.woms.mumdroid.core.udpproto.Ping
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServerPingCodecTest {

    @Test
    fun legacyRequest_is12BytesWithLeadingZeros() {
        val packet = ServerPingCodec.encodeLegacy(0x1122334455667788L)
        assertEquals(12, packet.size)
        assertEquals(0, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(0, packet[2].toInt())
        assertEquals(0, packet[3].toInt())
        val echoed = ByteBuffer.wrap(packet, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(0x1122334455667788L, echoed)
    }

    @Test
    fun legacyReply_decodesVersionUsersAndTimestamp() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x010500)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(99L)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8)
        buf.putInt(40)
        buf.putInt(72000)
        val decoded = ServerPingCodec.decode(buf.array())
        assertNotNull(decoded)
        assertEquals(99L, decoded!!.timestamp)
        assertEquals(8, decoded.users)
        assertEquals(40, decoded.maxUsers)
        assertEquals(72000, decoded.bandwidth)
        assertEquals("1.5.0", decoded.version)
    }

    @Test
    fun protobufRequest_setsExtendedFlag() {
        val packet = ServerPingCodec.encodeProtobuf(12345L)
        assertEquals(1, packet[0].toInt())
        val ping = Ping.parseFrom(packet.copyOfRange(1, packet.size))
        assertEquals(12345L, ping.timestamp)
        assertTrue(ping.requestExtendedInformation)
    }

    @Test
    fun protobufReply_decodesV2VersionAndCounts() {
        val versionV2 = (1L shl 48) or (5L shl 32) or (4L shl 16)
        val body = Ping.newBuilder()
            .setTimestamp(7L)
            .setServerVersionV2(versionV2)
            .setUserCount(3)
            .setMaxUserCount(25)
            .setMaxBandwidthPerUser(96000)
            .build()
            .toByteArray()
        val packet = ByteArray(1 + body.size)
        packet[0] = ProtoUdpCodec.HEADER_PING.toByte()
        System.arraycopy(body, 0, packet, 1, body.size)
        val decoded = ServerPingCodec.decode(packet)
        assertNotNull(decoded)
        assertEquals(7L, decoded!!.timestamp)
        assertEquals(3, decoded.users)
        assertEquals(25, decoded.maxUsers)
        assertEquals("1.5.4", decoded.version)
        assertTrue(decoded.versionIsV2)
    }

    @Test
    fun legacyReply_versionIsNotMarkedAsV2() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x0105ff) // saturated legacy encoding of 1.5.857
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(1L)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(2)
        buf.putInt(10)
        buf.putInt(72000)
        val decoded = ServerPingCodec.decode(buf.array())
        assertNotNull(decoded)
        assertEquals("1.5.255", decoded!!.version)
        assertFalse(decoded.versionIsV2)
    }

    @Test
    fun protobufConnectivityOnly_leavesCountsUnset() {
        val body = Ping.newBuilder().setTimestamp(1L).build().toByteArray()
        val packet = byteArrayOf(1) + body
        val decoded = ServerPingCodec.decode(packet)
        assertNotNull(decoded)
        assertNull(decoded!!.users)
        assertNull(decoded.maxUsers)
        assertNull(decoded.version)
    }

    @Test
    fun formatVersions() {
        assertEquals("1.3.5", ServerPingCodec.formatLegacyVersion(0x010305))
        assertEquals("1.5.0", ServerPingCodec.formatVersionV2(0x0001000500000000L))
        assertNull(ServerPingCodec.formatLegacyVersion(0))
        assertNull(ServerPingCodec.formatVersionV2(0L))
    }

    @Test
    fun rejectUnknownPayload() {
        assertNull(ServerPingCodec.decode(byteArrayOf(0x20, 0x01)))
        assertNull(ServerPingCodec.decode(ByteArray(0)))
    }

    @Test
    fun protoUdpEncodePing_defaultDoesNotRequestExtended() {
        val packet = ProtoUdpCodec.encodePing(1L)
        val ping = Ping.parseFrom(packet.copyOfRange(1, packet.size))
        assertFalse(ping.requestExtendedInformation)
        assertArrayEquals(
            ProtoUdpCodec.encodePing(1L, requestExtended = false),
            packet,
        )
    }

    @Test
    fun mergeKeepsPreviousMetadataWhenIncomingIsBlank() {
        val old = ServerPingInfo(pingMs = 20, users = 4, maxUsers = 10, version = "1.4.0", reachable = true)
        val incoming = ServerPingInfo(pingMs = 35, reachable = true, updatedAtMs = 9)
        val merged = old.mergedWith(incoming)
        assertEquals(35, merged.pingMs)
        assertEquals(4, merged.users)
        assertEquals(10, merged.maxUsers)
        assertEquals("1.4.0", merged.version)
        assertTrue(merged.reachable)
    }

    @Test
    fun merge_exactV2VersionSurvivesLegacyReplyArrivingSecond() {
        // Protobuf reply first (exact 1.5.857), then the legacy reply whose
        // 16.8.8 field saturates the patch (1.5.255). The exact value must win.
        val protobufFirst = ServerPingInfo(
            pingMs = 30, version = "1.5.857", versionPrecise = true, reachable = true,
        )
        val legacySecond = ServerPingInfo(
            pingMs = 28, users = 2, maxUsers = 10, version = "1.5.255", reachable = true,
        )
        val merged = protobufFirst.mergedWith(legacySecond)
        assertEquals("1.5.857", merged.version)
        assertTrue(merged.versionPrecise)
        assertEquals(28, merged.pingMs)
        assertEquals(2, merged.users)
        assertEquals(10, merged.maxUsers)
    }

    @Test
    fun packetsToSend_unknownVersionSendsBoth() {
        assertEquals(
            listOf(ServerPingPacket.LEGACY, ServerPingPacket.PROTOBUF),
            ServerPingCodec.packetsToSend(version = null),
        )
        assertEquals(
            listOf(ServerPingPacket.LEGACY, ServerPingPacket.PROTOBUF),
            ServerPingCodec.packetsToSend(ServerPingInfo()),
        )
    }

    @Test
    fun packetsToSend_legacyServerUsesV1Only() {
        val info = ServerPingInfo(version = "1.4.0", reachable = true)
        assertEquals(listOf(ServerPingPacket.LEGACY), ServerPingCodec.packetsToSend(info))
    }

    @Test
    fun packetsToSend_protobufEraUsesV2Only() {
        val precise = ServerPingInfo(version = "1.5.857", versionPrecise = true, reachable = true)
        val saturated = ServerPingInfo(version = "1.5.255", reachable = true)
        assertEquals(listOf(ServerPingPacket.PROTOBUF), ServerPingCodec.packetsToSend(precise))
        assertEquals(listOf(ServerPingPacket.PROTOBUF), ServerPingCodec.packetsToSend(saturated))
    }

    @Test
    fun pingInterval_clampsToFiveSecondSteps() {
        assertEquals(5, AppSettings.clampServerPingIntervalSeconds(1))
        assertEquals(15, AppSettings.clampServerPingIntervalSeconds(14))
        assertEquals(15, AppSettings.clampServerPingIntervalSeconds(16))
        assertEquals(60, AppSettings.clampServerPingIntervalSeconds(90))
        assertEquals(15, AppSettings().serverPingIntervalSeconds)
        assertFalse(AppSettings().autoServerPing)
    }

    @Test
    fun merge_exactV2VersionReplacesSaturatedLegacyReply() {
        // Legacy reply first (saturated 1.5.255), then the protobuf reply with
        // the exact version. The exact value must win in this order too.
        val legacyFirst = ServerPingInfo(
            pingMs = 30, users = 2, maxUsers = 10, version = "1.5.255", reachable = true,
        )
        val protobufSecond = ServerPingInfo(
            pingMs = 28, users = 2, maxUsers = 10, version = "1.5.857", versionPrecise = true, reachable = true,
        )
        val merged = legacyFirst.mergedWith(protobufSecond)
        assertEquals("1.5.857", merged.version)
        assertTrue(merged.versionPrecise)
    }
}
