package dev.woms.mumdroid.core.model

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
    val grant: Long = ChanACL.NONE.toLong(),
    val deny: Long = ChanACL.NONE.toLong(),
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

/**
 * Channel ACL query / editor snapshot: the wire `ACL` without its `query`
 * flag. Lives in `core.model` so the editor and the UI can name it without
 * depending on the network package.
 */
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
