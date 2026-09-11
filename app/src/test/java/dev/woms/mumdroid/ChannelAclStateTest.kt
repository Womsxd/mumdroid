package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.net.ChannelPasswordAcl
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.PermissionDenied
import dev.woms.mumdroid.service.ChannelAclState
import dev.woms.mumdroid.service.PasswordApply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAclStateTest {

    private val state = ChannelAclState()

    private fun aclOf(channelId: Int, password: String = ""): ACL {
        val builder = ACL.newBuilder().setChannelId(channelId).setInheritAcls(true)
        if (password.isNotEmpty()) {
            builder.addAcls(
                ACL.ChanACL.newBuilder()
                    .setGroup("${ChanACL.Group.ACCESS_TOKEN}$password")
                    .setGrant(ChannelPasswordAcl.PASSWORD_PERMS)
                    .setApplyHere(true)
                    .setApplySubs(false)
                    .setInherited(false)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun denied(
        channelId: Int = 4,
        permission: Long = ChanACL.ENTER.toLong(),
        type: PermissionDenied.DenyType = PermissionDenied.DenyType.Permission,
    ): PermissionDenied = PermissionDenied.newBuilder()
        .setChannelId(channelId)
        .setPermission(permission.toInt())
        .setType(type)
        .build()

    private val restricted = Channel(id = 4, name = "Gate", isEnterRestricted = true)

    // ---- create-with-password deferral ----

    @Test
    fun queueCreatePassword_emptyPasswordIsNotQueued() {
        state.queueCreatePassword(parentId = 0, name = "Sub", password = "   ")
        assertNull(state.maybeCreatePassword(isNew = true, channel = Channel(id = 5, parentId = 0, name = "Sub")))
    }

    @Test
    fun maybeCreatePassword_onlyMatchesTheEchoedChannel() {
        state.queueCreatePassword(parentId = 0, name = "Sub", password = " pw ")
        assertNull(state.maybeCreatePassword(isNew = true, channel = Channel(id = 5, parentId = 0, name = "Other")))
        // Not a new channel (an update) must not consume the password either.
        assertNull(state.maybeCreatePassword(isNew = false, channel = Channel(id = 5, parentId = 0, name = "Sub")))
        assertEquals(5 to "pw", state.maybeCreatePassword(isNew = true, channel = Channel(id = 5, parentId = 0, name = "Sub")))
    }

    // ---- ACL reply ----

    @Test
    fun onAcl_publishesTheSnapshotAndTheParsedPassword() {
        assertNull(state.onAcl(aclOf(4, "secret")))
        assertEquals(4, state.channelAcl.value!!.channelId)
        assertEquals(ChannelAclPassword(4, "secret"), state.channelAclPassword.value)
    }

    @Test
    fun onAcl_completesAPendingPasswordApply() {
        // The first apply has no snapshot: it must ask for a query first.
        assertEquals(PasswordApply.Query(4), state.preparePasswordApply(4, "secret"))
        val pending = state.onAcl(aclOf(4))
        assertNotNull(pending)
        assertEquals("secret", pending!!.second)
        // Only once: the reply of a later unrelated query must not resend it.
        assertNull(state.onAcl(aclOf(4)))
    }

    @Test
    fun onAcl_ignoresAPendingApplyForAnotherChannel() {
        state.preparePasswordApply(4, "secret")
        assertNull(state.onAcl(aclOf(9)))
    }

    @Test
    fun preparePasswordApply_reusesTheCachedSnapshot() {
        state.onAcl(aclOf(4))
        val action = state.preparePasswordApply(4, " secret ")
        assertTrue(action is PasswordApply.Send)
        assertEquals("secret", (action as PasswordApply.Send).password)
        assertNull(action.snap.aclsList.firstOrNull())
    }

    @Test
    fun preparePasswordApply_emptyPasswordWithoutSnapshotIsANoOp() {
        assertNull(state.preparePasswordApply(4, "  "))
    }

    @Test
    fun passwordAclMessage_returnsNullWhenThePasswordIsUnchanged() {
        val snap = aclOf(4, "secret")
        assertNull(state.passwordAclMessage(snap, "secret"))
    }

    @Test
    fun passwordAclMessage_updatesTheParsedPasswordFlow() {
        val snap = aclOf(4, "old")
        val msg = state.passwordAclMessage(snap, "new")
        assertNotNull(msg)
        assertEquals("new", ChannelPasswordAcl.extractPassword(msg!!))
        assertEquals(ChannelAclPassword(4, "new"), state.channelAclPassword.value)
    }

    // ---- denied-join prompt ----

    @Test
    fun promptForChannelPassword_onlyForDeniedEnterOnARestrictedChannel() {
        var deniedMessage: String? = null
        assertFalse(
            state.promptForChannelPassword(
                denied(permission = ChanACL.SPEAK.toLong()),
                restricted,
                ChanACL.ENTER.toLong(),
                onDenied = { deniedMessage = it },
                passwordDeniedMessage = { "denied: $it" },
            ),
        )
        assertFalse(
            state.promptForChannelPassword(
                denied(),
                Channel(id = 4, name = "Open"),
                ChanACL.ENTER.toLong(),
                onDenied = { deniedMessage = it },
                passwordDeniedMessage = { "denied: $it" },
            ),
        )
        assertNull(state.channelPasswordPrompt.value)
        assertNull(deniedMessage)
    }

    @Test
    fun promptForChannelPassword_marksARetryOnlyForTheSameChannel() {
        state.notePasswordJoin(4)
        var deniedMessage: String? = null
        assertTrue(
            state.promptForChannelPassword(
                denied(),
                restricted,
                ChanACL.ENTER.toLong(),
                onDenied = { deniedMessage = it },
                passwordDeniedMessage = { "denied: $it" },
            ),
        )
        val prompt = state.channelPasswordPrompt.value!!
        assertEquals(4, prompt.channelId)
        assertEquals("Gate", prompt.channelName)
        assertTrue(prompt.retry)
        assertEquals("denied: Gate", deniedMessage)
        // The latch is consumed: the next denial is not a retry again.
        state.notePasswordJoin(4)
        assertTrue(state.consumePasswordJoin(4))
        state.promptForChannelPassword(
            denied(),
            restricted,
            ChanACL.ENTER.toLong(),
            onDenied = {},
            passwordDeniedMessage = { it },
        )
        assertFalse(state.channelPasswordPrompt.value!!.retry)
    }

    @Test
    fun consumePasswordJoin_onlyForTheExpectedChannel() {
        state.notePasswordJoin(4)
        assertFalse(state.consumePasswordJoin(9))
        assertTrue(state.consumePasswordJoin(4))
        assertFalse(state.consumePasswordJoin(4))
    }

    // ---- user names + clear ----

    @Test
    fun onQueryUsers_mergesIdsAndNames() {
        state.onQueryUsers(listOf(1), listOf("Alice"))
        state.onQueryUsers(listOf(2), listOf("Bob"))
        assertEquals("Alice", state.aclUserNames.value.displayName(1))
        assertEquals(2, state.aclUserNames.value.idOf("bob"))
    }

    @Test
    fun clear_resetsEveryFlowAndPendingRequest() {
        state.onAcl(aclOf(4, "secret"))
        state.onQueryUsers(listOf(1), listOf("Alice"))
        state.notePasswordJoin(4)
        state.clear()
        assertNull(state.channelAcl.value)
        assertNull(state.channelAclPassword.value)
        assertNull(state.channelPasswordPrompt.value)
        assertEquals("#1", state.aclUserNames.value.displayName(1))
        assertFalse(state.consumePasswordJoin(4))
    }
}
