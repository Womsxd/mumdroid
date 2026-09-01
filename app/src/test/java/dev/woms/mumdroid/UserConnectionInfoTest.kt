package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.proto.UserStats
import dev.woms.mumdroid.core.proto.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConnectionInfoTest {

    @Test
    fun hostAddress_ipv4Mapped() {
        val bytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(),
            192.toByte(), 168.toByte(), 1, 20,
        )
        assertEquals("192.168.1.20", UserConnectionInfo.formatHostAddress(bytes))
    }

    @Test
    fun hostAddress_ipv6() {
        val bytes = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
        )
        assertEquals("2001:db8:0:0:0:0:0:1", UserConnectionInfo.formatHostAddress(bytes))
    }

    @Test
    fun durationParts_matchesDesktopBreakdown() {
        val parts = UserConnectionInfo.durationParts(
            7 * 24 * 3600 + 2 * 24 * 3600 + 3 * 3600 + 4 * 60 + 5,
        )
        assertEquals(1, parts.weeks)
        assertEquals(2, parts.days)
        assertEquals(3, parts.hours)
        assertEquals(4, parts.minutes)
        assertEquals(5, parts.seconds)
    }

    @Test
    fun fromProto_mapsPingAndUdpStats() {
        val from = UserStats.Stats.newBuilder().setGood(10).setLate(2).setLost(1).setResync(0).build()
        val to = UserStats.Stats.newBuilder().setGood(9).setLate(1).setLost(0).setResync(1).build()
        val msg = UserStats.newBuilder()
            .setSession(7)
            .setTcpPackets(40)
            .setUdpPackets(80)
            .setTcpPingAvg(12.5f)
            .setTcpPingVar(4f)
            .setUdpPingAvg(8f)
            .setUdpPingVar(1f)
            .setFromClient(from)
            .setFromServer(to)
            .setOnlinesecs(125)
            .setIdlesecs(5)
            .setBandwidth(12500)
            .setOpus(true)
            .setVersion(
                Version.newBuilder()
                    .setVersionV1(0x010500)
                    .setRelease("mumdroid 1.0-abc")
                    .setOs("Android")
                    .setOsVersion("15")
                    .build(),
            )
            .setAddress(ByteString.copyFrom(byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(),
                10, 0, 0, 1,
            )))
            .build()

        val info = UserConnectionInfo.fromProto(msg, "alice")
        assertEquals(7, info.session)
        assertEquals("alice", info.userName)
        assertEquals("10.0.0.1", info.address)
        assertEquals("1.5.0 (mumdroid 1.0-abc)", info.versionDisplay)
        assertEquals("Android (15)", info.osDisplay)
        assertTrue(info.hasConnectionDetails)
        assertEquals(true, info.opus)
        assertEquals(40, info.tcpPackets)
        assertEquals(80, info.udpPackets)
        assertEquals(10, info.fromClient?.good)
        assertEquals(9, info.fromServer?.good)
        assertEquals(125, info.onlineSecs)
        assertEquals(5, info.idleSecs)
        assertEquals(12500, info.bandwidthBytesPerSec)
        assertEquals("100.0 kbit/s", UserConnectionInfo.formatBandwidth(info.bandwidthBytesPerSec!!))
    }

    @Test
    fun fromProto_statsOnlyKeepsPreviousConnectionDetails() {
        val first = UserConnectionInfo.fromProto(
            UserStats.newBuilder()
                .setSession(3)
                .setVersion(Version.newBuilder().setVersionV1(0x010500).setRelease("pc").build())
                .setAddress(ByteString.copyFrom(byteArrayOf(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(),
                    1, 2, 3, 4,
                )))
                .setTcpPackets(1)
                .build(),
            "bob",
        )
        val refresh = UserConnectionInfo.fromProto(
            UserStats.newBuilder()
                .setSession(3)
                .setStatsOnly(true)
                .setTcpPackets(9)
                .setTcpPingAvg(3.25f)
                .build(),
            "bob",
            first,
        )
        assertEquals("1.2.3.4", refresh.address)
        assertEquals("pc", refresh.release)
        assertTrue(refresh.hasConnectionDetails)
        assertEquals(9, refresh.tcpPackets)
        assertEquals(3.25f, refresh.tcpPingAvg, 0.01f)
    }

    @Test
    fun fromProto_omittedOpusStaysUnknown() {
        val info = UserConnectionInfo.fromProto(UserStats.newBuilder().setSession(1).build(), "x")
        assertNull(info.opus)
        assertFalse(info.hasConnectionDetails)
    }
}
