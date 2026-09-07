package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.QueryUsers

/**
 * One ChanACL row as the desktop editor stores it (`ChanACL` in `ACL.h`).
 *
 * [userId] is [ChanACL.UserId.ANY] when the rule targets [group] instead of a
 * registered account. Temporary lookup placeholders are `< -1` and are not
 * written (`ACLEditor::accept`).
 */
data class ChanAclRule(
    val inherited: Boolean = false,
    val userId: Int = ChanACL.UserId.ANY,
    val group: String = "",
    val applyHere: Boolean = true,
    val applySubs: Boolean = true,
    val grant: Int = ChanACL.NONE,
    val deny: Int = ChanACL.NONE,
)

/** One `ACL.ChanGroup` as the desktop editor stores it. */
data class ChanAclGroup(
    val name: String,
    val inherited: Boolean = true,
    val inherit: Boolean = true,
    val inheritable: Boolean = true,
    val add: List<Int> = emptyList(),
    val remove: List<Int> = emptyList(),
    val inheritedMembers: List<Int> = emptyList(),
)

/** Channel ACL query / editor snapshot (`MumbleProto::ACL` without `query`). */
data class ChanAclSnapshot(
    val channelId: Int,
    val inheritAcls: Boolean = true,
    val acls: List<ChanAclRule> = emptyList(),
    val groups: List<ChanAclGroup> = emptyList(),
)

/**
 * Registered-user id/name cache used by the desktop ACL editor
 * (`ACLEditor::userName` / `returnQuery`).
 */
data class AclUserNames(
    val idToName: Map<Int, String> = emptyMap(),
    val nameToId: Map<String, Int> = emptyMap(),
) {
    fun displayName(userId: Int): String = idToName[userId] ?: "#$userId"

    fun idOf(name: String): Int? = nameToId[name.lowercase()]

    fun merge(ids: List<Int>, names: List<String>): AclUserNames {
        if (ids.size != names.size) return this
        val nextIds = idToName.toMutableMap()
        val nextNames = nameToId.toMutableMap()
        for (i in ids.indices) {
            val id = ids[i]
            val name = names[i]
            nextIds[id] = name
            nextNames[name.lowercase()] = id
        }
        return AclUserNames(nextIds, nextNames)
    }
}

/**
 * Desktop `ACLEditor::accept` / `ServerHandler::requestACL` encode helpers.
 */
object ChanAclWrite {
    fun query(channelId: Int): ACL =
        ACL.newBuilder().setChannelId(channelId).setQuery(true).build()

    fun fromProto(msg: ACL): ChanAclSnapshot =
        ChanAclSnapshot(
            channelId = msg.channelId,
            inheritAcls = msg.inheritAcls,
            acls = msg.aclsList.map(::ruleFromProto),
            groups = msg.groupsList.map(::groupFromProto),
        )

    /**
     * Builds the write payload: `query` unset, inherited ACL rows omitted,
     * `userId < -1` omitted, and groups that are purely inherited with no
     * local add/remove omitted. Matches `ACLEditor::accept`.
     */
    fun toWriteMessage(snapshot: ChanAclSnapshot): ACL {
        val builder = ACL.newBuilder()
            .setChannelId(snapshot.channelId)
            .setInheritAcls(snapshot.inheritAcls)
        for (acl in snapshot.acls) {
            if (!shouldSendAcl(acl.inherited, acl.userId)) continue
            builder.addAcls(ruleToProto(acl))
        }
        for (group in snapshot.groups) {
            if (!shouldSendGroup(
                    inherited = group.inherited,
                    inherit = group.inherit,
                    inheritable = group.inheritable,
                    addCount = group.add.size,
                    removeCount = group.remove.size,
                )
            ) {
                continue
            }
            builder.addGroups(groupToProto(group))
        }
        return builder.build()
    }

    /** Desktop: skip inherited rows and unresolved `userId < -1` placeholders. */
    fun shouldSendAcl(inherited: Boolean, userId: Int): Boolean =
        !inherited && userId >= ChanACL.UserId.ANY

    /**
     * Desktop: skip a group that is inherited, still inheriting members, still
     * inheritable, and has no local add/remove.
     */
    fun shouldSendGroup(
        inherited: Boolean,
        inherit: Boolean,
        inheritable: Boolean,
        addCount: Int,
        removeCount: Int,
    ): Boolean = !(inherited && inherit && inheritable && addCount == 0 && removeCount == 0)

    /** Desktop `ACLEditor::id`: look up names the server has not cached yet. */
    fun queryUsersByName(names: Collection<String>): QueryUsers {
        val builder = QueryUsers.newBuilder()
        names.forEach { builder.addNames(it) }
        return builder.build()
    }

    /** Desktop `ACLEditor` refresh by registered id. */
    fun queryUsersById(ids: Collection<Int>): QueryUsers {
        val builder = QueryUsers.newBuilder()
        ids.filter { it >= ChanACL.UserId.SUPERUSER }.forEach { builder.addIds(it) }
        return builder.build()
    }

    private fun ruleFromProto(acl: ACL.ChanACL): ChanAclRule =
        ChanAclRule(
            inherited = acl.inherited,
            userId = if (acl.hasUserId()) acl.userId else ChanACL.UserId.ANY,
            group = if (acl.hasUserId()) "" else acl.group,
            applyHere = acl.applyHere,
            applySubs = acl.applySubs,
            grant = acl.grant,
            deny = acl.deny,
        )

    private fun groupFromProto(group: ACL.ChanGroup): ChanAclGroup =
        ChanAclGroup(
            name = group.name,
            inherited = group.inherited,
            inherit = group.inherit,
            inheritable = group.inheritable,
            add = group.addList.toList(),
            remove = group.removeList.toList(),
            inheritedMembers = group.inheritedMembersList.toList(),
        )

    private fun ruleToProto(acl: ChanAclRule): ACL.ChanACL {
        val builder = ACL.ChanACL.newBuilder()
            .setApplyHere(acl.applyHere)
            .setApplySubs(acl.applySubs)
            .setInherited(false)
            .setGrant(acl.grant)
            .setDeny(acl.deny)
        if (acl.userId >= ChanACL.UserId.SUPERUSER) {
            builder.setUserId(acl.userId)
        } else {
            builder.setGroup(acl.group)
        }
        return builder.build()
    }

    private fun groupToProto(group: ChanAclGroup): ACL.ChanGroup {
        val builder = ACL.ChanGroup.newBuilder()
            .setName(group.name)
            .setInherit(group.inherit)
            .setInheritable(group.inheritable)
        group.add.filter { it >= ChanACL.UserId.SUPERUSER }.forEach { builder.addAdd(it) }
        group.remove.filter { it >= ChanACL.UserId.SUPERUSER }.forEach { builder.addRemove(it) }
        return builder.build()
    }
}
