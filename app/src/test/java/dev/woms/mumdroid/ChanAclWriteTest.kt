package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclGroup
import dev.woms.mumdroid.core.model.ChanAclRule
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.model.ChanAclWrite
import dev.woms.mumdroid.core.proto.ACL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChanAclWriteTest {

    @Test
    fun query_setsChannelAndQueryFlag() {
        val msg = ChanAclWrite.query(4)
        assertEquals(4, msg.channelId)
        assertTrue(msg.query)
    }

    @Test
    fun toWriteMessage_skipsInheritedAndUnresolvedUserIds() {
        val sent = ChanAclWrite.toWriteMessage(
            ChanAclSnapshot(
                channelId = 3,
                inheritAcls = false,
                acls = listOf(
                    ChanAclRule(
                        inherited = true,
                        group = ChanACL.Group.ALL,
                        grant = ChanACL.ENTER.toLong(),
                    ),
                    ChanAclRule(
                        userId = -2,
                        grant = ChanACL.SPEAK.toLong(),
                    ),
                    ChanAclRule(
                        userId = 7,
                        applyHere = true,
                        applySubs = false,
                        grant = ChanACL.TEXT_MESSAGE.toLong(),
                    ),
                    ChanAclRule(
                        group = ChanACL.Group.AUTH,
                        grant = ChanACL.SPEAK.toLong(),
                        deny = ChanACL.WHISPER.toLong(),
                    ),
                ),
            ),
        )
        assertFalse(sent.query)
        assertEquals(3, sent.channelId)
        assertFalse(sent.inheritAcls)
        assertEquals(2, sent.aclsCount)
        assertTrue(sent.getAcls(0).hasUserId())
        assertEquals(7, sent.getAcls(0).userId)
        assertFalse(sent.getAcls(0).applySubs)
        assertFalse(sent.getAcls(0).inherited)
        assertFalse(sent.getAcls(1).hasUserId())
        assertEquals(ChanACL.Group.AUTH, sent.getAcls(1).group)
        assertEquals(ChanACL.SPEAK, sent.getAcls(1).grant)
        assertEquals(ChanACL.WHISPER, sent.getAcls(1).deny)
    }

    @Test
    fun toWriteMessage_skipsPureInheritedGroups() {
        val sent = ChanAclWrite.toWriteMessage(
            ChanAclSnapshot(
                channelId = 1,
                groups = listOf(
                    ChanAclGroup(
                        name = ChanACL.Group.ADMIN,
                        inherited = true,
                        inherit = true,
                        inheritable = true,
                    ),
                    ChanAclGroup(
                        name = "mods",
                        inherited = true,
                        inherit = true,
                        inheritable = true,
                        add = listOf(2, -3),
                        remove = listOf(4),
                    ),
                ),
            ),
        )
        assertEquals(1, sent.groupsCount)
        assertEquals("mods", sent.getGroups(0).name)
        assertEquals(listOf(2), sent.getGroups(0).addList)
        assertEquals(listOf(4), sent.getGroups(0).removeList)
        assertEquals(0, sent.getGroups(0).inheritedMembersCount)
    }

    @Test
    fun fromProto_roundTripsLocalRules() {
        val incoming = ACL.newBuilder()
            .setChannelId(9)
            .setInheritAcls(true)
            .addAcls(
                ACL.ChanACL.newBuilder()
                    .setInherited(true)
                    .setGroup(ChanACL.Group.ALL)
                    .setGrant(ChanACL.TRAVERSE)
                    .build(),
            )
            .addAcls(
                ACL.ChanACL.newBuilder()
                    .setInherited(false)
                    .setUserId(1)
                    .setApplyHere(true)
                    .setApplySubs(true)
                    .setGrant(ChanACL.MUTE_DEAFEN)
                    .build(),
            )
            .addGroups(
                ACL.ChanGroup.newBuilder()
                    .setName(ChanACL.Group.ADMIN)
                    .setInherited(false)
                    .setInherit(false)
                    .setInheritable(true)
                    .addAdd(8)
                    .build(),
            )
            .build()
        val snap = ChanAclWrite.fromProto(incoming)
        assertEquals(9, snap.channelId)
        assertEquals(2, snap.acls.size)
        assertEquals(ChanACL.UserId.ANY, snap.acls[0].userId)
        assertEquals(1, snap.acls[1].userId)
        val sent = ChanAclWrite.toWriteMessage(snap)
        assertEquals(1, sent.aclsCount)
        assertEquals(1, sent.getAcls(0).userId)
        assertEquals(1, sent.groupsCount)
        assertEquals(ChanACL.Group.ADMIN, sent.getGroups(0).name)
        assertFalse(sent.getGroups(0).inherit)
        assertTrue(sent.getGroups(0).inheritable)
    }

    @Test
    fun queryUsers_filtersUnregisteredIds() {
        val byId = ChanAclWrite.queryUsersById(listOf(ChanACL.UserId.UNREGISTERED, 3, 0))
        assertEquals(listOf(3, 0), byId.idsList)
        val byName = ChanAclWrite.queryUsersByName(listOf("Ada", "bob"))
        assertEquals(listOf("Ada", "bob"), byName.namesList)
    }

    @Test
    fun aclUserNames_mergesAndLooksUp() {
        val names = AclUserNames().merge(listOf(3, 4), listOf("Ada", "Bob"))
        assertEquals("Ada", names.displayName(3))
        assertEquals("#9", names.displayName(9))
        assertEquals(4, names.idOf("BOB"))
        assertNull(names.idOf("missing"))
        val skipped = names.merge(listOf(1), listOf("a", "b"))
        assertEquals("Ada", skipped.displayName(3))
    }
}
