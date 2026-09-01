package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStateMerge
import dev.woms.mumdroid.core.proto.UserState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStateMergeTest {

    @Test
    fun newUser_requiresNameAndSession() {
        val nameless = UserState.newBuilder().setSession(4).setSuppress(true).build()
        assertFalse(UserStateMerge.shouldApply(existing = null, nameless))

        val noSession = UserState.newBuilder().setName("bob").setSuppress(true).build()
        assertFalse(UserStateMerge.shouldApply(existing = null, noSession))

        val ok = UserState.newBuilder().setSession(4).setName("bob").setSuppress(true).build()
        assertTrue(UserStateMerge.shouldApply(existing = null, ok))
    }

    @Test
    fun existingUser_acceptsPartialSuppressUpdate() {
        val existing = User(session = 4, name = "bob", suppress = true)
        val lift = UserState.newBuilder().setSession(4).setSuppress(false).build()
        assertTrue(UserStateMerge.shouldApply(existing, lift))
    }

    @Test
    fun afterRemove_partialUpdateIsIgnored() {
        val lateSuppress = UserState.newBuilder().setSession(4).setSuppress(true).build()
        assertFalse(UserStateMerge.shouldApply(existing = null, lateSuppress))
        val lateUnsuppress = UserState.newBuilder().setSession(4).setSuppress(false).build()
        assertFalse(UserStateMerge.shouldApply(existing = null, lateUnsuppress))
    }
}
