package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.service.TimedBanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A timed ban is assembled from two replies, and both paths (the stats reply and
 * the delayed kick timer) race for the same kick: exactly one of them may win,
 * otherwise the target is banned twice.
 */
class TimedBanStateTest {

    private val state = TimedBanState()

    private fun victim(session: Int = 7) = User(session = session, name = "spammer", hash = "AB")

    private fun ban(
        name: String = "spammer",
        hash: String = "AB",
        address: ByteArray = byteArrayOf(1, 2, 3, 4),
        duration: Int = 0,
    ) = BanEntry(
        address = address,
        mask = 32,
        name = name,
        hash = hash,
        reason = "spam",
        duration = duration,
        start = "2026-01-01T00:00:00Z",
    )

    private fun arm(
        session: Int = 7,
        user: User? = victim(session),
        duration: Int = 60,
        snapshot: List<BanEntry>? = null,
    ) = state.begin(
        session = session,
        user = user,
        reason = " spam ",
        duration = duration,
        banCertificate = true,
        banIp = false,
        banListSnapshot = snapshot,
    )

    @Test
    fun begin_zeroDurationIsAPlainBan() {
        assertFalse(arm(duration = 0))
    }

    @Test
    fun begin_missingUserCannotBeMatchedAgainstTheReply() {
        assertFalse(arm(user = null))
    }

    @Test
    fun begin_trimsTheReasonAndReturnsTheKick() {
        assertTrue(arm())
        val kick = state.sendKick()
        assertNotNull(kick)
        assertEquals("spam", kick!!.reason)
        assertTrue(kick.banCertificate)
        assertFalse(kick.banIp)
    }

    @Test
    fun sendKick_isClaimedOnlyOnce() {
        arm()
        assertNotNull(state.sendKick())
        assertNull(state.sendKick())
    }

    @Test
    fun sendKickWithAddress_ignoresAnotherSession() {
        arm(session = 7)
        assertNull(state.sendKickWithAddress(9, byteArrayOf(9)))
        assertNotNull(state.sendKick())
    }

    @Test
    fun sendKickWithAddress_claimsTheKickFromTheTimerPath() {
        arm()
        assertNotNull(state.sendKickWithAddress(7, byteArrayOf(1, 2, 3, 4)))
        assertNull(state.sendKick())
    }

    @Test
    fun patchBanList_usesTheAddressCapturedFromStats() {
        arm(snapshot = emptyList())
        state.sendKickWithAddress(7, byteArrayOf(1, 2, 3, 4))
        // Same-named user on another IP is a different person.
        val other = ban(address = byteArrayOf(9, 9, 9, 9))
        val patched = state.patchBanList(listOf(other, ban()))
        assertNotNull(patched)
        assertEquals(60, patched!![1].duration)
        assertEquals(0, patched[0].duration)
    }

    @Test
    fun patchBanList_noPendingBanLeavesTheListAlone() {
        assertNull(state.patchBanList(listOf(ban())))
    }

    @Test
    fun patchBanList_isConsumedOnce() {
        arm(snapshot = emptyList())
        assertNotNull(state.patchBanList(listOf(ban())))
        // A second reply (e.g. a concurrent admin refresh) must not re-patch.
        assertNull(state.patchBanList(listOf(ban())))
    }

    @Test
    fun patchBanList_snapshotDiffPrefersTheEntryTheServerJustAppended() {
        val existing = ban(name = "spammer", address = byteArrayOf(1, 2, 3, 4))
        arm(snapshot = listOf(existing))
        // The reply repeats the old entry and appends the new one: only the
        // appended entry may be patched.
        val patched = state.patchBanList(listOf(existing, ban(address = byteArrayOf(5, 5, 5, 5))))
        assertNotNull(patched)
        assertEquals(0, patched!![0].duration)
        assertEquals(60, patched[1].duration)
    }

    @Test
    fun matchesRemoval_onlyForTheTrackedSessionAndABan() {
        arm(session = 7)
        assertFalse(state.matchesRemoval(7, banned = false))
        assertFalse(state.matchesRemoval(9, banned = true))
        assertTrue(state.matchesRemoval(7, banned = true))
    }

    @Test
    fun reset_clearsThePendingBanAndTheKickClaim() {
        arm()
        state.reset()
        assertNull(state.sendKick())
        assertNull(state.patchBanList(listOf(ban())))
        assertFalse(state.matchesRemoval(7, banned = true))
    }
}
