package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclDraft
import dev.woms.mumdroid.core.net.ChanAclGroup
import dev.woms.mumdroid.core.net.ChanAclRule
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.core.net.ChanAclWrite
import dev.woms.mumdroid.core.net.ChannelAclEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAclEditTest {

    // ---- defaults ----

    @Test
    fun fromSnapshot_prependsTheDefaultRuleAndNeverSendsIt() {
        val draft = ChannelAclEdit.fromSnapshot(
            ChanAclSnapshot(channelId = 3, inheritAcls = false, acls = listOf(groupRule(ChanACL.Group.AUTH))),
        )
        assertEquals(2, draft.rules.size)
        val default = draft.rules[0]
        assertTrue(default.inherited)
        assertEquals(ChanACL.Group.ALL, default.group)
        assertEquals(ChanACL.UserId.ANY, default.userId)
        assertEquals(ChanACL.DEFAULT.toLong(), default.grant)
        assertEquals((ChanACL.ALL and ChanACL.DEFAULT.inv()).toLong(), default.deny)

        val sent = ChanAclWrite.toWriteMessage(ChannelAclEdit.toSnapshot(draft))
        assertFalse(sent.inheritAcls)
        assertEquals(1, sent.aclsCount)
        assertEquals(ChanACL.Group.AUTH, sent.getAcls(0).group)
    }

    @Test
    fun inheritedCount_isThePrefixMurmurSendsFirst() {
        val draft = withRules(inheritedRule("all"), inheritedRule("auth"), groupRule("in"))
        assertEquals(3, ChannelAclEdit.inheritedCount(draft))
        assertEquals(listOf(0, 1, 2, 3), ChannelAclEdit.visibleRules(draft))
        assertEquals(
            listOf(0, 3),
            ChannelAclEdit.visibleRules(ChannelAclEdit.setInheritAcls(draft, false)),
        )
    }

    @Test
    fun inheritedRulesRejectEveryEdit() {
        val draft = withRules(inheritedRule("all"), groupRule("in"))
        assertEquals(draft, ChannelAclEdit.removeRule(draft, 1))
        assertEquals(draft, ChannelAclEdit.moveRule(draft, 1, 2))
        assertEquals(draft, ChannelAclEdit.setApplyHere(draft, 1, false))
        assertEquals(draft, ChannelAclEdit.setApplySubs(draft, 1, false))
        assertEquals(draft, ChannelAclEdit.setGroup(draft, 1, "auth"))
        assertEquals(draft, ChannelAclEdit.setUser(draft, 1, "Ada", AclUserNames()))
        assertEquals(draft, ChannelAclEdit.togglePermission(draft, 1, ChanACL.SPEAK, true, true))
        assertFalse(ChannelAclEdit.rowEditable(draft.rules[1]))
    }

    // ---- rows ----

    @Test
    fun addRule_startsWithNoPermissionsAndAppliesEverywhere() {
        val draft = ChannelAclEdit.addRule(withRules())
        val added = draft.rules.last()
        assertEquals(ChanACL.Group.ALL, added.group)
        assertEquals(ChanACL.UserId.ANY, added.userId)
        assertTrue(added.applyHere)
        assertTrue(added.applySubs)
        assertFalse(added.inherited)
        assertEquals(0L, added.grant)
        assertEquals(0L, added.deny)
    }

    @Test
    fun removeRule_dropsLocalRows() {
        val draft = withRules(groupRule("in"), groupRule("out"))
        val next = ChannelAclEdit.removeRule(draft, 1)
        assertEquals(listOf(ChanACL.Group.ALL, ChanACL.Group.OUT), next.rules.map { it.group })
    }

    @Test
    fun moveRule_staysInsideTheBlockTheChannelOwns() {
        // [default(inherited), all(inherited), in, out]
        val draft = withRules(inheritedRule("all"), groupRule("in"), groupRule("out"))
        val labels = { d: ChanAclDraft -> d.rules.map { it.group } }

        assertEquals(listOf("all", "all", "out", "in"), labels(ChannelAclEdit.moveRule(draft, 3, 2)))
        assertEquals(listOf("all", "all", "out", "in"), labels(ChannelAclEdit.moveRule(draft, 2, 3)))
        // The first row the channel owns cannot be lifted above a parent's.
        assertEquals(draft, ChannelAclEdit.moveRule(draft, 2, 0))
        // Nor can an inherited row be moved at all.
        assertEquals(draft, ChannelAclEdit.moveRule(draft, 1, 3))
        // A drag past either end clamps instead of dropping the row.
        assertEquals(draft, ChannelAclEdit.moveRule(draft, 2, -5))
        assertEquals(draft, ChannelAclEdit.moveRule(draft, 3, 99))
    }

    @Test
    fun moveRule_movesByMoreThanOneRow() {
        val draft = withRules(groupRule("a"), groupRule("b"), groupRule("c"))
        val moved = ChannelAclEdit.moveRule(draft, 1, 3)
        assertEquals(listOf("all", "b", "c", "a"), moved.rules.map { it.group })
    }

    @Test
    fun applyScope_alwaysLeavesOneScopeOn() {
        val draft = withRules(groupRule("in"))
        val index = 1

        val hereOff = ChannelAclEdit.setApplyHere(draft, index, false)
        assertFalse(hereOff.rules[index].applyHere)
        assertTrue(hereOff.rules[index].applySubs)

        // Turning off the last remaining scope turns the other one back on,
        // because an entry that applies nowhere is silently dead.
        val subsOff = ChannelAclEdit.setApplySubs(hereOff, index, false)
        assertTrue(subsOff.rules[index].applyHere)
        assertFalse(subsOff.rules[index].applySubs)
    }

    // ---- permissions ----

    @Test
    fun togglePermission_keepsAllowAndDenyExclusive() {
        val draft = withRules(groupRule("in"))

        val allowed = ChannelAclEdit.togglePermission(draft, 1, ChanACL.SPEAK, allow = true, checked = true)
        assertTrue(ChanACL.has(allowed.rules[1].grant, ChanACL.SPEAK))
        assertFalse(ChanACL.has(allowed.rules[1].deny, ChanACL.SPEAK))

        val denied = ChannelAclEdit.togglePermission(allowed, 1, ChanACL.SPEAK, allow = false, checked = true)
        assertTrue(ChanACL.has(denied.rules[1].deny, ChanACL.SPEAK))
        assertFalse(ChanACL.has(denied.rules[1].grant, ChanACL.SPEAK))

        val cleared = ChannelAclEdit.togglePermission(denied, 1, ChanACL.SPEAK, allow = false, checked = false)
        assertEquals(0L, cleared.rules[1].deny)
        assertEquals(0L, cleared.rules[1].grant)
    }

    @Test
    fun togglePermission_movesListenWithEnter() {
        val draft = withRules(groupRule("in"))

        val allowed = ChannelAclEdit.togglePermission(draft, 1, ChanACL.ENTER, allow = true, checked = true)
        assertTrue(ChanACL.has(allowed.rules[1].grant, ChanACL.LISTEN))

        val denied = ChannelAclEdit.togglePermission(draft, 1, ChanACL.ENTER, allow = false, checked = true)
        assertTrue(ChanACL.has(denied.rules[1].deny, ChanACL.LISTEN))
        assertFalse(ChanACL.has(denied.rules[1].grant, ChanACL.LISTEN))

        // Unchecking Enter is not a change of intent, and Leave moves nothing.
        val unchecked = ChannelAclEdit.togglePermission(allowed, 1, ChanACL.ENTER, allow = true, checked = false)
        assertFalse(ChanACL.has(unchecked.rules[1].grant, ChanACL.ENTER))
        assertTrue(ChanACL.has(unchecked.rules[1].grant, ChanACL.LISTEN))
    }

    @Test
    fun rulePermissionEnabled_locksTheRowWhileWriteIsGranted() {
        val write = groupRule("in", grant = ChanACL.WRITE.toLong())
        assertTrue(ChannelAclEdit.rulePermissionEnabled(write, ChanACL.WRITE))
        assertTrue(ChannelAclEdit.rulePermissionEnabled(write, ChanACL.SPEAK))
        assertFalse(ChannelAclEdit.rulePermissionEnabled(write, ChanACL.ENTER))

        val readOnly = groupRule("in")
        assertTrue(ChannelAclEdit.rulePermissionEnabled(readOnly, ChanACL.ENTER))
        assertFalse(ChannelAclEdit.rulePermissionEnabled(inheritedRule("all"), ChanACL.SPEAK))
    }

    // ---- targets ----

    @Test
    fun setGroup_emptyNameFallsBackToAllAndDropsTheUser() {
        val draft = withRules(userRule(4))
        val next = ChannelAclEdit.setGroup(draft, 1, "   ")
        assertEquals(ChanACL.Group.ALL, next.rules[1].group)
        assertEquals(ChanACL.UserId.ANY, next.rules[1].userId)
    }

    @Test
    fun setUser_parksAnUnknownNameUntilTheServerAnswers() {
        val draft = withRules(groupRule("in"))

        val pending = ChannelAclEdit.setUser(draft, 1, "Ada", AclUserNames())
        assertEquals(-2, pending.rules[1].userId)
        assertEquals(setOf("Ada"), ChannelAclEdit.pendingNames(pending))
        assertEquals("Ada", ChannelAclEdit.ruleLabel(pending, AclUserNames(), 1))

        // The same name reuses its placeholder instead of allocating another.
        assertEquals(-2, ChannelAclEdit.setUser(pending, 1, "ada", AclUserNames()).rules[1].userId)

        // The QueryUsers reply binds the registered id.
        val names = AclUserNames().merge(listOf(7), listOf("Ada"))
        val resolved = ChannelAclEdit.resolveNames(pending, names)
        assertEquals(7, resolved.rules[1].userId)
        assertTrue(resolved.pendingNames.isEmpty())
        assertEquals("Ada", ChannelAclEdit.ruleLabel(resolved, names, 1))
        assertEquals("#9", ChannelAclEdit.ruleLabel(withRules(userRule(9)), names, 1))
    }

    @Test
    fun setUser_bindsCachedNamesAndUnknownIdsAtSaveTime() {
        val draft = withRules(groupRule("in"))
        val names = AclUserNames().merge(listOf(7), listOf("Ada"))

        val known = ChannelAclEdit.setUser(draft, 1, "ada", names)
        assertEquals(7, known.rules[1].userId)
        assertTrue(known.pendingNames.isEmpty())
        assertTrue(ChanAclWrite.shouldSendAcl(known.rules[1].inherited, known.rules[1].userId))

        // An unresolved lookup is dropped on the wire, exactly like desktop.
        val unknown = ChannelAclEdit.setUser(draft, 1, "Nobody", names)
        assertFalse(ChanAclWrite.shouldSendAcl(unknown.rules[1].inherited, unknown.rules[1].userId))
        assertEquals(0, ChanAclWrite.toWriteMessage(ChannelAclEdit.toSnapshot(unknown)).aclsCount)
    }

    @Test
    fun setUser_emptyNameGoesBackToTheGroupTarget() {
        val draft = withRules(userRule(4))
        val next = ChannelAclEdit.setUser(draft, 1, "  ", AclUserNames())
        assertEquals(ChanACL.UserId.ANY, next.rules[1].userId)
        // A row with no group would apply to nobody, so it falls back to `all`.
        assertEquals(ChanACL.Group.ALL, next.rules[1].group)
        assertEquals("@all", ChannelAclEdit.ruleLabel(next, AclUserNames(), 1))

        // A row that already names a group keeps it.
        val withGroup = ChannelAclEdit.setUser(withRules(groupRule("auth")), 1, "Ada", AclUserNames())
        val back = ChannelAclEdit.setUser(withGroup, 1, "", AclUserNames())
        assertEquals(ChanACL.UserId.ANY, back.rules[1].userId)
        assertEquals("auth", back.rules[1].group)
    }

    @Test
    fun resolveNames_dropsLookupsNoRowRefersToAnyMore() {
        val pending = ChannelAclEdit.setUser(withRules(groupRule("in")), 1, "Ada", AclUserNames())
        val removed = ChannelAclEdit.removeRule(pending, 1)
        val resolved = ChannelAclEdit.resolveNames(removed, AclUserNames().merge(listOf(7), listOf("Ada")))
        assertTrue(resolved.pendingNames.isEmpty())
    }

    @Test
    fun targetId_resolvesGroupMembersTheSameWay() {
        val draft = withRules()
        val (withMember, member) = ChannelAclEdit.targetId(draft, "Ada", AclUserNames())
        assertEquals(-2, member)
        val group = ChannelAclEdit.addGroup(withMember, "mods")
        val withAdded = ChannelAclEdit.addMember(group, "mods", member!!)

        val names = AclUserNames().merge(listOf(7), listOf("Ada"))
        val resolved = ChannelAclEdit.resolveNames(withAdded, names)
        assertEquals(listOf(7), ChannelAclEdit.group(resolved, "mods")!!.add)

        val (_, empty) = ChannelAclEdit.targetId(draft, "   ", AclUserNames())
        assertNull(empty)
    }

    @Test
    fun candidates_seedsTheCacheWithOnlineRegisteredUsers() {
        val names = ChannelAclEdit.candidates(
            AclUserNames().merge(listOf(3), listOf("Ada")),
            listOf(
                User(session = 1, name = "Bob", userId = 9),
                User(session = 2, name = "Guest", userId = ChanACL.UserId.UNREGISTERED),
            ),
        )
        assertEquals(9, names.idOf("bob"))
        assertEquals(3, names.idOf("ada"))
        assertNull(names.idOf("guest"))
    }

    // ---- groups ----

    @Test
    fun addGroup_normalizesTheNameAndStartsInheriting() {
        val draft = ChannelAclEdit.addGroup(withRules(), "  Mods ")
        assertEquals(1, draft.groups.size)
        val group = draft.groups[0]
        assertEquals("mods", group.name)
        assertFalse(group.inherited)
        assertTrue(group.inherit)
        assertTrue(group.inheritable)
        assertEquals(draft, ChannelAclEdit.addGroup(draft, "mods"))
    }

    @Test
    fun removeGroup_handsInheritedGroupsBackToTheParent() {
        val draft = withGroups(
            ChanAclGroup(
                name = "admin",
                inherited = true,
                inherit = false,
                inheritable = false,
                add = listOf(3),
                remove = listOf(4),
            ),
            ChanAclGroup(name = "mods", inherited = false, add = listOf(5)),
        )

        val cleared = ChannelAclEdit.removeGroup(draft, "admin")
        assertEquals(2, cleared.groups.size)
        val admin = ChannelAclEdit.group(cleared, "admin")!!
        assertTrue(admin.inherit)
        assertTrue(admin.inheritable)
        assertTrue(admin.add.isEmpty())
        assertTrue(admin.remove.isEmpty())

        assertEquals(1, ChannelAclEdit.removeGroup(cleared, "mods").groups.size)
    }

    @Test
    fun groupFlagsAndMembersRoundTrip() {
        val draft = ChannelAclEdit.addGroup(withRules(), "mods")

        val flagged = ChannelAclEdit.setGroupInheritable(draft, "mods", false)
        assertFalse(ChannelAclEdit.group(flagged, "mods")!!.inheritable)
        assertTrue(ChannelAclEdit.group(flagged, "mods")!!.inherit)

        val added = ChannelAclEdit.addMember(flagged, "mods", 5)
        assertEquals(listOf(5), ChannelAclEdit.group(added, "mods")!!.add)
        assertEquals(added, ChannelAclEdit.addMember(added, "mods", 5))

        val excluded = ChannelAclEdit.excludeMember(added, "mods", 9)
        assertEquals(listOf(9), ChannelAclEdit.group(excluded, "mods")!!.remove)
        assertEquals(excluded, ChannelAclEdit.excludeMember(excluded, "mods", 9))

        val trimmed = ChannelAclEdit.removeExcludedMember(excluded, "mods", 9)
        assertTrue(ChannelAclEdit.group(trimmed, "mods")!!.remove.isEmpty())
        assertTrue(ChannelAclEdit.group(ChannelAclEdit.removeMember(trimmed, "mods", 5), "mods")!!.add.isEmpty())
    }

    @Test
    fun groupEditsOnAMissingGroupAreNoOps() {
        val draft = withRules()
        assertEquals(draft, ChannelAclEdit.addMember(draft, "ghost", 1))
        assertEquals(draft, ChannelAclEdit.excludeMember(draft, "ghost", 1))
        assertEquals(draft, ChannelAclEdit.setGroupInherit(draft, "ghost", false))
        assertEquals(draft, ChannelAclEdit.removeGroup(draft, "ghost"))
    }

    @Test
    fun sendDropsPureInheritedGroupsAndInheritedRows() {
        val draft = withGroups(
            ChanAclGroup(name = "admin", inherited = true, inherit = true, inheritable = true),
            ChanAclGroup(name = "mods", inherited = false, add = listOf(5)),
        )
        val sent = ChanAclWrite.toWriteMessage(ChannelAclEdit.toSnapshot(draft))
        assertEquals(1, sent.groupsCount)
        assertEquals("mods", sent.getGroups(0).name)
        assertEquals(0, sent.aclsCount)
    }

    // ---- helpers ----

    private fun withRules(vararg rules: ChanAclRule): ChanAclDraft =
        ChannelAclEdit.fromSnapshot(ChanAclSnapshot(channelId = 1, acls = rules.toList()))

    private fun withGroups(vararg groups: ChanAclGroup): ChanAclDraft =
        ChannelAclEdit.fromSnapshot(ChanAclSnapshot(channelId = 1, groups = groups.toList()))

    private fun inheritedRule(group: String): ChanAclRule =
        ChanAclRule(inherited = true, group = group)

    private fun groupRule(group: String, grant: Long = 0, deny: Long = 0): ChanAclRule =
        ChanAclRule(group = group, grant = grant, deny = deny)

    private fun userRule(userId: Int): ChanAclRule = ChanAclRule(userId = userId)
}
