package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.RegisteredUser
import dev.woms.mumdroid.service.AdminListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list flows are tri-state (`null` = never fetched), and the refresh flags
 * must never be left spinning on a disconnected session — that is the state the
 * UI would otherwise show forever.
 */
class AdminListStateTest {

    private val state = AdminListState()

    private fun user(id: Int, name: String) = RegisteredUser(userId = id, name = name)

    private fun ban(name: String, duration: Int = 0) = BanEntry(
        address = byteArrayOf(1, 2, 3, 4),
        mask = 32,
        name = name,
        hash = "",
        reason = "r",
        duration = duration,
    )

    // ---- user list ----

    @Test
    fun beginUserListRequest_disconnectedClearsSpinnerAndSkips() {
        assertFalse(state.beginUserListRequest(clear = true, connected = false))
        assertFalse(state.userListRefreshing.value)
    }

    @Test
    fun beginUserListRequest_clearDropsStaleListImmediately() {
        state.onUserList(listOf(user(1, "a")))
        // A forced refresh must not show the previous server's rows while the
        // reply is in flight.
        assertTrue(state.beginUserListRequest(clear = true, connected = true))
        assertNull(state.registeredUsers.value)
        assertFalse(state.userListRefreshing.value)
    }

    @Test
    fun beginUserListRequest_keepSetsRefreshing() {
        state.onUserList(listOf(user(1, "a")))
        assertTrue(state.beginUserListRequest(clear = false, connected = true))
        assertNotNull(state.registeredUsers.value)
        assertTrue(state.userListRefreshing.value)
    }

    @Test
    fun onUserList_sortsCaseInsensitivelyAndStopsSpinner() {
        state.beginUserListRequest(clear = false, connected = true)
        state.onUserList(listOf(user(2, "Zoe"), user(1, "alice"), user(3, "Bob")))
        assertEquals(listOf("alice", "Bob", "Zoe"), state.registeredUsers.value!!.map { it.name })
        assertFalse(state.userListRefreshing.value)
    }

    @Test
    fun renameRegisteredUser_blankOrDuplicateIsRejected() {
        state.onUserList(listOf(user(1, "alice"), user(2, "bob")))
        assertNull(state.renameRegisteredUser(1, "   "))
        assertNull(state.renameRegisteredUser(1, "bob"))
        assertEquals(listOf("alice", "bob"), state.registeredUsers.value!!.map { it.name })
    }

    @Test
    fun renameRegisteredUser_renamesInPlaceAndReturnsTheEntry() {
        state.onUserList(listOf(user(1, "alice"), user(2, "bob")))
        val entry = state.renameRegisteredUser(1, "  carol ")
        assertEquals(RegisteredUser(1, "carol"), entry)
        assertEquals(listOf("carol", "bob"), state.registeredUsers.value!!.map { it.name })
    }

    @Test
    fun unregisterUser_superUserIsUntouchable() {
        state.onUserList(listOf(user(0, "SuperUser"), user(1, "alice")))
        assertNull(state.unregisterUser(0))
        assertEquals(2, state.registeredUsers.value!!.size)
    }

    @Test
    fun unregisterUser_removesAndReturnsTheEntry() {
        state.onUserList(listOf(user(1, "alice"), user(2, "bob")))
        assertEquals(RegisteredUser(1), state.unregisterUser(1))
        assertEquals(listOf("bob"), state.registeredUsers.value!!.map { it.name })
    }

    // ---- ban list ----

    @Test
    fun beginBanListRequest_disconnectedClearsSpinnerAndSkips() {
        assertFalse(state.beginBanListRequest(clear = true, connected = false))
        assertFalse(state.banListRefreshing.value)
    }

    @Test
    fun beginBanListRequest_clearDropsStaleListAndKeepsTheOldSpinnerOff() {
        state.setBanList(listOf(ban("x")))
        assertTrue(state.beginBanListRequest(clear = true, connected = true))
        assertNull(state.banList.value)
        assertFalse(state.banListRefreshing.value)
    }

    @Test
    fun banSnapshot_keepsThePreKickListForDiffing() {
        state.setBanList(listOf(ban("old")))
        state.beginBanListRequest(clear = false, connected = true)
        assertEquals(listOf("old"), state.banSnapshot()!!.map { it.name })
        state.endBanListRequest()
        assertFalse(state.banListRefreshing.value)
    }

    @Test
    fun clear_resetsFlowsAndFlags() {
        state.onUserList(listOf(user(1, "a")))
        state.setBanList(listOf(ban("b")))
        state.beginUserListRequest(clear = false, connected = true)
        state.beginBanListRequest(clear = false, connected = true)
        state.clear()
        assertNull(state.registeredUsers.value)
        assertNull(state.banList.value)
        assertFalse(state.userListRefreshing.value)
        assertFalse(state.banListRefreshing.value)
    }
}
