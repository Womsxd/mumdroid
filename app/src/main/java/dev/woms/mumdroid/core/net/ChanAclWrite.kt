package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclGroup
import dev.woms.mumdroid.core.model.ChanAclRule
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.net.ChanAclWrite.toWriteMessage
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.QueryUsers

/**
 * Desktop `ACLEditor::accept` / `ServerHandler::requestACL` encode helpers.
 * The models it works over live in `core.model` ([ChanAclRule],
 * [ChanAclGroup], [ChanAclSnapshot]); this object only does the wire
 * conversion.
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
        val acls = snapshot.acls
            .filter { shouldSendAcl(it.inherited, it.userId) }
            .map(::ruleToProto)
        val groups = snapshot.groups
            .filter {
                shouldSendGroup(
                    inherited = it.inherited,
                    inherit = it.inherit,
                    inheritable = it.inheritable,
                    addCount = it.add.size,
                    removeCount = it.remove.size,
                )
            }
            .map(::groupToProto)
        return writePayload(
            base = ACL.newBuilder()
                .setChannelId(snapshot.channelId)
                .setInheritAcls(snapshot.inheritAcls)
                .build(),
            acls = acls,
            groups = groups,
        )
    }

    /**
     * Reassembles an ACL write payload from proto pieces the caller has already
     * filtered: `query` unset and the incoming rows replaced. Shared by
     * [toWriteMessage] and [ChannelPasswordAcl.apply], which both start from a
     * server ACL message and must not echo `query` or dropped rows back.
     */
    fun writePayload(
        base: ACL,
        acls: List<ACL.ChanACL>,
        groups: List<ACL.ChanGroup>,
    ): ACL {
        val builder = base.toBuilder().clearQuery().clearAcls().clearGroups()
        acls.forEach { builder.addAcls(it) }
        groups.forEach { builder.addGroups(it) }
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
            grant = ChanAclCodec.fromProtoUInt32(acl.grant),
            deny = ChanAclCodec.fromProtoUInt32(acl.deny),
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
            .setGrant(ChanAclCodec.toProtoUInt32(acl.grant))
            .setDeny(ChanAclCodec.toProtoUInt32(acl.deny))
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
