package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.proto.UserStats
import dev.woms.mumdroid.core.proto.Version
import dev.woms.mumdroid.service.AdminUserStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `UserStats` reply is merged over the previous snapshot for the same session,
 * but a snapshot from another session must never leak into it.
 */
class AdminUserStatsTest {

    private val stats = AdminUserStats()

    private fun partial(session: Int, ping: Int = 0) = UserStats.newBuilder()
        .setSession(session)
        .setStatsOnly(true)
        .setTcpPingAvg(ping.toFloat())
        .build()

    private fun full(session: Int, address: ByteArray = byteArrayOf(1, 2, 3, 4)) = UserStats.newBuilder()
        .setSession(session)
        .setVersion(
            Version.newBuilder()
                .setVersionV1(0x010500)
                .setRelease("1.5.0")
                .setOs("Linux")
                .setOsVersion("6.1")
                .build(),
        )
        .setAddress(ByteString.copyFrom(address))
        .setTcpPackets(10)
        .setUdpPackets(20)
        .build()

    @Test
    fun onStats_publishesTheParsedSnapshot() {
        stats.onStats(full(7), "alice")
        val snapshot = stats.userStats.value!!
        assertEquals(7, snapshot.session)
        assertEquals("alice", snapshot.userName)
        assertEquals("1.5.0", snapshot.release)
    }

    @Test
    fun onStats_mergesAPartialReplyOverTheSameSession() {
        stats.onStats(full(7), "alice")
        stats.onStats(partial(7, ping = 33), "alice")
        val snapshot = stats.userStats.value!!
        // Connection details came from the first reply and must survive.
        assertEquals("1.5.0", snapshot.release)
        assertEquals(33f, snapshot.tcpPingAvg, 0.01f)
    }

    @Test
    fun onStats_doesNotMergeAcrossSessions() {
        stats.onStats(full(7), "alice")
        val other = stats.onStats(partial(9), "bob")
        assertEquals(9, other.session)
        assertEquals("", other.release)
        assertEquals("bob", other.userName)
    }

    @Test
    fun clearIfSession_onlyDropsTheMatchingSnapshot() {
        stats.onStats(full(7), "alice")
        stats.clearIfSession(9)
        assertEquals(7, stats.userStats.value!!.session)
        stats.clearIfSession(7)
        assertNull(stats.userStats.value)
    }

    @Test
    fun clear_dropsTheSnapshot() {
        stats.onStats(full(7), "alice")
        stats.clear()
        assertNull(stats.userStats.value)
    }
}
