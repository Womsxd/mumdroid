package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserUpdate
import dev.woms.mumdroid.core.net.UserStateMerge
import dev.woms.mumdroid.core.proto.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStateMergeTest {

    @Test
    fun newUser_requiresNameAndSession() {
        val nameless = UserState.newBuilder().setSession(4).setSuppress(true).build()
        assertFalse(UserStateMerge.shouldApply(existing = null, UserStateMerge.toUpdate(nameless)!!))

        val noSession = UserState.newBuilder().setName("bob").setSuppress(true).build()
        assertNull(UserStateMerge.toUpdate(noSession))

        val ok = UserState.newBuilder().setSession(4).setName("bob").setSuppress(true).build()
        assertTrue(UserStateMerge.shouldApply(existing = null, UserStateMerge.toUpdate(ok)!!))
    }

    @Test
    fun existingUser_acceptsPartialSuppressUpdate() {
        val existing = User(session = 4, name = "bob", suppress = true)
        val lift = UserState.newBuilder().setSession(4).setSuppress(false).build()
        assertTrue(UserStateMerge.shouldApply(existing, UserStateMerge.toUpdate(lift)!!))
    }

    @Test
    fun afterRemove_partialUpdateIsIgnored() {
        val lateSuppress = UserState.newBuilder().setSession(4).setSuppress(true).build()
        assertFalse(
            UserStateMerge.shouldApply(existing = null, UserStateMerge.toUpdate(lateSuppress)!!),
        )
        val lateUnsuppress = UserState.newBuilder().setSession(4).setSuppress(false).build()
        assertFalse(
            UserStateMerge.shouldApply(existing = null, UserStateMerge.toUpdate(lateUnsuppress)!!),
        )
    }

    /**
     * The merge is why the update keeps presence: an omitted field has to stay
     * distinct from one explicitly set to its default.
     */
    @Test
    fun toUpdate_keepsPresenceDistinctFromDefaults() {
        val partial = UserState.newBuilder()
            .setSession(4)
            .setChannelId(0)
            .setSuppress(false)
            .build()
        val update = UserStateMerge.toUpdate(partial)!!

        assertTrue("an explicit channel_id 0 is present", update.channelId != null)
        assertEquals(0, update.channelId)
        assertTrue("an explicit suppress=false is present", update.suppress != null)
        assertNull("an omitted name stays null", update.name)
        assertNull("an omitted mute stays null", update.mute)
    }

    @Test
    fun toUpdate_rejectsSessionZero() {
        assertNull(UserStateMerge.toUpdate(UserState.newBuilder().setName("bob").build()))
        assertNull(UserStateMerge.toUpdate(UserState.newBuilder().setSession(0).setName("b").build()))
    }

    @Test
    fun shouldApply_isTheSameRuleForTheDomainType() {
        assertTrue(
            UserStateMerge.shouldApply(
                existing = null,
                UserUpdate(session = 4, name = "bob"),
            ),
        )
        assertFalse(
            UserStateMerge.shouldApply(
                existing = null,
                UserUpdate(session = 4, name = ""),
            ),
        )
    }
}
