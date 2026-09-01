package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ServerRemovalKind
import dev.woms.mumdroid.core.model.UserRemoveNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRemoveNoticeTest {

    @Test
    fun localKick_isNotANetworkDrop() {
        val notice = UserRemoveNotice.of(
            isLocal = true,
            hasActor = true,
            actorName = "Admin",
            targetName = "me",
            reason = "spam",
            banned = false,
        )
        assertEquals(ServerRemovalKind.KICKED, notice?.kind)
        assertTrue(notice!!.isLocal)
        assertEquals("Admin", notice.actorName)
        assertEquals("spam", notice.reason)
    }

    @Test
    fun localBan_isBanned() {
        val notice = UserRemoveNotice.of(
            isLocal = true,
            hasActor = true,
            actorName = "Admin",
            targetName = "me",
            reason = "",
            banned = true,
        )
        assertEquals(ServerRemovalKind.BANNED, notice?.kind)
        assertTrue(notice!!.isLocal)
    }

    @Test
    fun localGhost_isServerRemoval() {
        val notice = UserRemoveNotice.of(
            isLocal = true,
            hasActor = false,
            actorName = "",
            targetName = "me",
            reason = "You connected to the server from another device",
            banned = false,
        )
        assertEquals(ServerRemovalKind.REMOVED, notice?.kind)
        assertTrue(notice!!.isLocal)
    }

    @Test
    fun otherUserKick_isChatNotice() {
        val notice = UserRemoveNotice.of(
            isLocal = false,
            hasActor = true,
            actorName = "Admin",
            targetName = "Bob",
            reason = "idle",
            banned = false,
        )
        assertEquals(ServerRemovalKind.KICKED, notice?.kind)
        assertFalse(notice!!.isLocal)
        assertEquals("Bob", notice.targetName)
    }

    @Test
    fun otherUserLeave_isNotARemoval() {
        assertNull(
            UserRemoveNotice.of(
                isLocal = false,
                hasActor = false,
                actorName = "",
                targetName = "Bob",
                reason = "",
                banned = false,
            ),
        )
    }
}
