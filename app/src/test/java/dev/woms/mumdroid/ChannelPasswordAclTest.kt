package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChannelPasswordAcl
import dev.woms.mumdroid.core.proto.ACL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPasswordAclTest {

    @Test
    fun extractPassword_readsHashGroup() {
        val msg = ACL.newBuilder()
            .setChannelId(4)
            .setInheritAcls(true)
            .addAcls(inheritedEnter())
            .addAcls(denyAll())
            .addAcls(allowPassword("secret"))
            .build()
        assertEquals("secret", ChannelPasswordAcl.extractPassword(msg))
        assertTrue(ChannelPasswordAcl.isPassword(msg.getAcls(2)))
        assertTrue(ChannelPasswordAcl.isPasswordDenyAll(msg.getAcls(1)))
        assertFalse(ChannelPasswordAcl.isPassword(msg.getAcls(0)))
    }

    @Test
    fun apply_addsDenyAllAndTokenGrant() {
        val msg = ACL.newBuilder()
            .setChannelId(4)
            .setInheritAcls(true)
            .addAcls(inheritedEnter())
            .addGroups(
                ACL.ChanGroup.newBuilder()
                    .setName("admin")
                    .setInherited(true)
                    .setInherit(true)
                    .setInheritable(true)
                    .build(),
            )
            .build()
        val sent = ChannelPasswordAcl.apply(msg, "  gate  ")!!
        assertFalse(sent.query)
        assertEquals(4, sent.channelId)
        assertTrue(sent.inheritAcls)
        assertEquals(2, sent.aclsCount)
        assertEquals(0, sent.groupsCount)
        assertTrue(ChannelPasswordAcl.isPasswordDenyAll(sent.getAcls(0)))
        assertTrue(ChannelPasswordAcl.isPassword(sent.getAcls(1)))
        assertEquals("#gate", sent.getAcls(1).group)
        assertFalse(sent.getAcls(1).applySubs)
        assertFalse(sent.getAcls(1).inherited)
    }

    @Test
    fun apply_updatesExistingPassword() {
        val msg = ACL.newBuilder()
            .setChannelId(1)
            .addAcls(denyAll())
            .addAcls(allowPassword("old"))
            .build()
        val sent = ChannelPasswordAcl.apply(msg, "new")!!
        assertEquals(2, sent.aclsCount)
        assertEquals("#new", sent.getAcls(1).group)
        assertEquals("old", ChannelPasswordAcl.extractPassword(msg))
    }

    @Test
    fun apply_clearsPasswordAndDenyAll() {
        val msg = ACL.newBuilder()
            .setChannelId(1)
            .addAcls(denyAll())
            .addAcls(allowPassword("old"))
            .addAcls(
                ACL.ChanACL.newBuilder()
                    .setApplyHere(true)
                    .setApplySubs(true)
                    .setInherited(false)
                    .setGroup("admin")
                    .setGrant(1)
                    .build(),
            )
            .build()
        val sent = ChannelPasswordAcl.apply(msg, "")!!
        assertEquals(1, sent.aclsCount)
        assertEquals("admin", sent.getAcls(0).group)
        assertEquals("", ChannelPasswordAcl.extractPassword(sent))
    }

    @Test
    fun apply_noChangeReturnsNull() {
        val msg = ACL.newBuilder()
            .setChannelId(1)
            .addAcls(denyAll())
            .addAcls(allowPassword("same"))
            .build()
        assertNull(ChannelPasswordAcl.apply(msg, "same"))
        assertNull(ChannelPasswordAcl.apply(msg, " same "))
    }

    @Test
    fun isPassword_acceptsLegacyMaskWithoutTraverse() {
        val legacy = ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(false)
            .setInherited(false)
            .setGroup("#old")
            .setGrant(ChannelPasswordAcl.PASSWORD_PERMS_LEGACY)
            .setDeny(0)
            .build()
        assertTrue(ChannelPasswordAcl.isPassword(legacy))
        val deny = ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(false)
            .setInherited(false)
            .setGroup("all")
            .setGrant(0)
            .setDeny(ChannelPasswordAcl.PASSWORD_PERMS_LEGACY)
            .build()
        assertTrue(ChannelPasswordAcl.isPasswordDenyAll(deny))
    }
        val msg = ChannelPasswordAcl.query(9)
        assertEquals(9, msg.channelId)
        assertTrue(msg.query)
    }

    private fun inheritedEnter(): ACL.ChanACL =
        ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(true)
            .setInherited(true)
            .setGroup("all")
            .setGrant(ChannelPasswordAcl.TRAVERSE or 0x4)
            .build()

    private fun denyAll(): ACL.ChanACL =
        ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(false)
            .setInherited(false)
            .setGroup("all")
            .setGrant(0)
            .setDeny(ChannelPasswordAcl.PASSWORD_PERMS)
            .build()

    private fun allowPassword(password: String): ACL.ChanACL =
        ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(false)
            .setInherited(false)
            .setGroup("#$password")
            .setGrant(ChannelPasswordAcl.PASSWORD_PERMS)
            .setDeny(0)
            .build()
}
