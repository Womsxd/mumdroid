package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.ACL

/**
 * Desktop `ACLEditor::updatePasswordACL` / `ChanACL::isPassword`.
 *
 * A channel password is two explicit ACLs: deny Enter/Speak/… to `all`, then
 * grant the same bits to `#<password>`. Clearing the field removes both.
 */
object ChannelPasswordAcl {
    /** Current desktop deny/allow mask, including Traverse. */
    val PASSWORD_PERMS: Int =
        ChanACL.ENTER or ChanACL.SPEAK or ChanACL.WHISPER or ChanACL.TEXT_MESSAGE or
            ChanACL.LINK_CHANNEL or ChanACL.TRAVERSE

    /** Older clients omitted Traverse from the password mask. */
    val PASSWORD_PERMS_LEGACY: Int =
        ChanACL.ENTER or ChanACL.SPEAK or ChanACL.WHISPER or ChanACL.TEXT_MESSAGE or
            ChanACL.LINK_CHANNEL

    fun isPassword(acl: ACL.ChanACL): Boolean {
        if (acl.hasUserId()) return false
        if (!acl.group.startsWith(ChanACL.Group.ACCESS_TOKEN.toString())) return false
        if (!acl.applyHere || acl.inherited) return false
        if (acl.deny != 0) return false
        val grant = ChanACL.fromProtoUInt32(acl.grant)
        if (grant and ChanACL.ENTER.toLong() == 0L) return false
        return grant == ChanACL.fromProtoUInt32(PASSWORD_PERMS) ||
            grant == ChanACL.fromProtoUInt32(PASSWORD_PERMS_LEGACY)
    }

    fun isPasswordDenyAll(acl: ACL.ChanACL): Boolean {
        if (acl.hasUserId()) return false
        if (acl.group != ChanACL.Group.ALL) return false
        if (!acl.applyHere || acl.inherited) return false
        if (acl.grant != 0) return false
        val deny = ChanACL.fromProtoUInt32(acl.deny)
        return deny == ChanACL.fromProtoUInt32(PASSWORD_PERMS) ||
            deny == ChanACL.fromProtoUInt32(PASSWORD_PERMS_LEGACY)
    }

    fun extractPassword(msg: ACL): String {
        val pw = msg.aclsList.lastOrNull(::isPassword) ?: return ""
        return pw.group.removePrefix(ChanACL.Group.ACCESS_TOKEN.toString())
    }

    /**
     * Returns an ACL write payload (`query` unset, inherited rows omitted),
     * or null when [password] already matches [msg].
     */
    fun apply(msg: ACL, password: String): ACL? {
        val trimmed = password.trim()
        if (extractPassword(msg) == trimmed) return null

        val acls = msg.aclsList.toMutableList()
        if (trimmed.isEmpty()) {
            acls.removeAll { isPassword(it) || isPasswordDenyAll(it) }
        } else {
            val index = acls.indexOfLast(::isPassword)
            if (index < 0) {
                acls += denyAll()
                acls += allowPassword(trimmed)
            } else {
                acls[index] = acls[index].toBuilder()
                    .setGroup("${ChanACL.Group.ACCESS_TOKEN}$trimmed")
                    .build()
            }
        }

        val builder = msg.toBuilder().clearQuery().clearAcls().clearGroups()
        acls.filter { !it.inherited }.forEach { builder.addAcls(it) }
        msg.groupsList.filter(::groupNeedsSend).forEach { group ->
            builder.addGroups(group.toBuilder().clearInheritedMembers().build())
        }
        return builder.build()
    }

    fun query(channelId: Int): ACL = ChanAclWrite.query(channelId)

    private fun denyAll(): ACL.ChanACL =
        passwordAcl(ChanACL.Group.ALL, grant = 0, deny = PASSWORD_PERMS)

    private fun allowPassword(password: String): ACL.ChanACL =
        passwordAcl("${ChanACL.Group.ACCESS_TOKEN}$password", grant = PASSWORD_PERMS, deny = 0)

    private fun passwordAcl(group: String, grant: Int, deny: Int): ACL.ChanACL =
        ACL.ChanACL.newBuilder()
            .setApplyHere(true)
            .setApplySubs(false)
            .setInherited(false)
            .setGroup(group)
            .setGrant(grant)
            .setDeny(deny)
            .build()

    /**
     * Desktop `ACLEditor::accept` skips groups that are purely inherited
     * with no local add/remove members.
     */
    private fun groupNeedsSend(group: ACL.ChanGroup): Boolean =
        ChanAclWrite.shouldSendGroup(
            inherited = group.inherited,
            inherit = group.inherit,
            inheritable = group.inheritable,
            addCount = group.addCount,
            removeCount = group.removeCount,
        )
}
