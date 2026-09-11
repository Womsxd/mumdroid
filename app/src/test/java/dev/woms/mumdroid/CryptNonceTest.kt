package dev.woms.mumdroid

import dev.woms.mumdroid.core.crypto.CryptNonce
import dev.woms.mumdroid.core.crypto.CryptPacketStats
import dev.woms.mumdroid.core.crypto.CryptReplayHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy UDP crypto bookkeeping: nonce direction, late/lost accounting and
 * the 256-entry replay memory. All of it used to live inline in
 * `CryptState.decrypt`, where only a full encrypt/decrypt round-trip could
 * reach it.
 */
class CryptNonceTest {

    private fun nonce(vararg bytes: Int) = ByteArray(16) { i ->
        if (i < bytes.size) bytes[i].toByte() else 0
    }

    private fun apply(current: ByteArray, ivByte: Int): Pair<CryptNonce.Plan, ByteArray> {
        val plan = CryptNonce.plan(current, ivByte)
        plan.apply(current)
        return plan to current
    }

    @Test
    fun increment_carriesUpTheLittleEndianNonce() {
        val n = nonce(0xff, 0xff, 0x00)
        CryptNonce.increment(n)
        assertEquals(0x00, n[0].toInt() and 0xff)
        assertEquals(0x00, n[1].toInt() and 0xff)
        assertEquals(0x01, n[2].toInt() and 0xff)
    }

    @Test
    fun decrement_borrowsDownTheLittleEndianNonce() {
        val n = nonce(0x00, 0x00, 0x01)
        CryptNonce.decrement(n)
        assertEquals(0xff, n[0].toInt() and 0xff)
        assertEquals(0xff, n[1].toInt() and 0xff)
        assertEquals(0x00, n[2].toInt() and 0xff)
    }

    @Test
    fun inOrder_plainAndWrapped() {
        val (plain, after) = apply(nonce(0x10, 0x20), 0x11)
        assertEquals(CryptNonce.Advance.IN_ORDER, plain.advance)
        assertEquals(0x11, after[0].toInt() and 0xff)
        assertEquals(0x20, after[1].toInt() and 0xff)
        assertEquals(0, plain.lost)

        // 0xff -> 0x00 wraps the first byte and carries into the second.
        val (wrapped, carried) = apply(nonce(0xff, 0x20), 0x00)
        assertEquals(CryptNonce.Advance.IN_ORDER, wrapped.advance)
        assertEquals(0x00, carried[0].toInt() and 0xff)
        assertEquals(0x21, carried[1].toInt() and 0xff)
    }

    @Test
    fun duplicateHeader_isRejectedWithoutDecrypting() {
        assertEquals(
            CryptNonce.Advance.DUPLICATE,
            CryptNonce.plan(nonce(0x10), 0x10).advance,
        )
        // The official client reaches the same verdict through its fallback
        // branch; either way the datagram is dropped, not decrypted.
        val current = nonce(0x10, 0x20)
        val plan = CryptNonce.plan(current, 0x10)
        plan.apply(current)
        assertEquals(0x10, current[0].toInt() and 0xff)
    }

    @Test
    fun gapForward_countsTheSkippedPackets() {
        val (plan, after) = apply(nonce(0x10), 0x14)
        assertEquals(CryptNonce.Advance.GAP_FORWARD, plan.advance)
        assertEquals(3, plan.lost)
        assertEquals(0, plan.late)
        assertFalse(plan.restore)
        assertEquals(0x14, after[0].toInt() and 0xff)
    }

    @Test
    fun gapAcrossAWrap_carriesTheHighBytes() {
        val (plan, after) = apply(nonce(0xfe, 0x20), 0x02)
        assertEquals(CryptNonce.Advance.GAP_WRAP, plan.advance)
        assertEquals(256 - 0xfe + 0x02 - 1, plan.lost)
        assertEquals(0x02, after[0].toInt() and 0xff)
        assertEquals(0x21, after[1].toInt() and 0xff)
    }

    @Test
    fun latePacket_isMarkedForRestore() {
        val (plan, after) = apply(nonce(0x20, 0x30), 0x1e)
        assertEquals(CryptNonce.Advance.LATE_RESTORE, plan.advance)
        assertEquals(1, plan.late)
        // A late packet must not be counted as loss.
        assertEquals(-1, plan.lost)
        assertTrue(plan.restore)
        assertEquals(0x1e, after[0].toInt() and 0xff)
        assertEquals(0x30, after[1].toInt() and 0xff)
    }

    @Test
    fun latePacketFromThePreviousRound_borrowsTheHighBytes() {
        // Current 0x02, header 0xfa: a negative diff far from zero, so the
        // high bytes step back one round.
        val (plan, after) = apply(nonce(0x02, 0x30), 0xfa)
        assertEquals(CryptNonce.Advance.LATE_RESTORE, plan.advance)
        assertEquals(1, plan.late)
        assertEquals(-1, plan.lost)
        assertEquals(0xfa, after[0].toInt() and 0xff)
        assertEquals(0x2f, after[1].toInt() and 0xff)
    }

    @Test
    fun headerFarAhead_stillCountsAsAForwardGap() {
        // Half a round ahead: the largest gap the protocol can express.
        val (plan, _) = apply(nonce(0x00), 0x80)
        assertEquals(CryptNonce.Advance.GAP_FORWARD, plan.advance)
        assertEquals(0x80 - 1, plan.lost)
    }

    @Test
    fun headerTooFarBehind_isRejected() {
        // Beyond the late window (30) and not a wrap: indistinguishable from a
        // replay, so the datagram is dropped.
        assertEquals(CryptNonce.Advance.UNKNOWN, CryptNonce.plan(nonce(0x40), 0x00).advance)
    }

    @Test
    fun replayHistory_remembersOnlyTheLastTwoNonceBytes() {
        val history = CryptReplayHistory()
        assertFalse(history.isReplay(0x10, 0x20))
        history.remember(0x10, 0x20)
        assertTrue(history.isReplay(0x10, 0x20))
        // A different second byte at the same first byte is a new packet.
        assertFalse(history.isReplay(0x10, 0x21))
        history.remember(0x10, 0x21)
        assertTrue(history.isReplay(0x10, 0x21))
        // The index is masked to a byte.
        history.remember(0x110, 0x30)
        assertTrue(history.isReplay(0x10, 0x30))
        history.clear()
        assertFalse(history.isReplay(0x10, 0x30))
    }

    @Test
    fun packetStats_countGoodAndLost() {
        val stats = CryptPacketStats()
        stats.onDecrypted(lost = 0, late = 0)
        assertEquals(1, stats.good)
        stats.onDecrypted(lost = 3, late = 0)
        assertEquals(2, stats.good)
        assertEquals(3, stats.lost)
    }

    @Test
    fun packetStats_doNotLetLatePacketsDriveCountersNegative() {
        val stats = CryptPacketStats()
        // A late packet reports lost = -1: with no loss recorded yet the
        // counter must stay at zero, not go negative.
        stats.onDecrypted(lost = -1, late = 1)
        assertEquals(0, stats.lost)
        assertEquals(1, stats.late)

        val withLoss = CryptPacketStats()
        withLoss.onDecrypted(lost = 5, late = 0)
        withLoss.onDecrypted(lost = -1, late = 1)
        assertEquals(4, withLoss.lost)
        assertEquals(1, withLoss.late)
    }

    @Test
    fun packetStats_resyncIsCountedOutsideDecrypt() {
        val stats = CryptPacketStats()
        stats.onResync()
        assertEquals(1, stats.resync)
        // A resync must not look like a decrypted packet.
        assertEquals(0, stats.good)
        stats.reset()
        assertEquals(0, stats.resync)
        assertEquals(0, stats.good)
    }
}
