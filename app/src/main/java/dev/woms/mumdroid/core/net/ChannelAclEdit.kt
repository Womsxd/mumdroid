package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.net.ChannelAclEdit.DEFAULT_RULE

/**
 * The draft a channel ACL editor edits: the desktop `ACLEditor`'s `qlACLs` /
 * `qlGroups` lists plus the unresolved name lookups it parks in `qhNameWait`.
 *
 * It holds the rows exactly as the editor lists them — including the rows
 * inherited from parent channels (read-only there) and the synthetic default
 * row that documents murmur's own defaults — because [ChanAclWrite] is what
 * decides which of them reach the server. Keeping the draft a picture of the
 * channel rather than of the payload is what lets the editor show a user what
 * the server will actually apply.
 *
 * [rules] always starts with [ChannelAclEdit.DEFAULT_RULE]; everything after it
 * is the server's answer, parents' rows first.
 */
data class ChanAclDraft(
    val channelId: Int,
    val inheritAcls: Boolean,
    val rules: List<ChanAclRule>,
    val groups: List<ChanAclGroup>,
    /** Unresolved lookups: negative placeholder id -> the name the user typed. */
    val pendingNames: Map<Int, String> = emptyMap(),
)

/**
 * The editing rules of the desktop ACL editor, as pure functions over
 * [ChanAclDraft].
 *
 * Every function mirrors a named `ACLEditor` slot or handler so the two
 * implementations can be compared line by line; they are kept out of the
 * composable so the decisions (ordering, inheritance, permission interactions)
 * can be tested without a Compose runtime.
 */
object ChannelAclEdit {

    /**
     * Desktop `ACLEditor` constructor: the list always opens with the implicit
     * default entry (`@all` with murmur's starting grants) so the editor states
     * what an unconfigured channel gives. It is marked inherited, which is both
     * true (it belongs to no channel) and what keeps `accept()` from writing it
     * back.
     */
    val DEFAULT_RULE: ChanAclRule =
        ChanAclRule(
            inherited = true,
            userId = ChanACL.UserId.ANY,
            group = ChanACL.Group.ALL,
            applyHere = true,
            applySubs = true,
            grant = ChanACL.DEFAULT.toLong(),
            deny = (ChanACL.ALL and ChanACL.DEFAULT.inv()).toLong(),
        )

    /** Seeds a draft from an ACL reply, prepending [DEFAULT_RULE]. */
    fun fromSnapshot(snapshot: ChanAclSnapshot): ChanAclDraft =
        ChanAclDraft(
            channelId = snapshot.channelId,
            inheritAcls = snapshot.inheritAcls,
            rules = listOf(DEFAULT_RULE) + snapshot.acls,
            groups = snapshot.groups,
        )

    /** The draft as the editor would send it; [ChanAclWrite] prunes what it must. */
    fun toSnapshot(draft: ChanAclDraft): ChanAclSnapshot =
        ChanAclSnapshot(
            channelId = draft.channelId,
            inheritAcls = draft.inheritAcls,
            acls = draft.rules,
            groups = draft.groups,
        )

    /**
     * Leading inherited rows: the desktop `numInheritACL` boundary. murmur
     * answers a query with the parents' rows first, so everything past this
     * prefix belongs to the channel being edited and is the only part that can
     * be reordered or removed.
     */
    fun inheritedCount(draft: ChanAclDraft): Int = draft.rules.takeWhile { it.inherited }.size

    /**
     * The row murmur's own defaults are shown as: the synthetic entry at index
     * 0. Desktop keeps it listed even when the channel does not inherit, since
     * the baseline applies either way.
     */
    fun defaultRules(draft: ChanAclDraft): List<Int> =
        if (draft.rules.isEmpty()) emptyList() else listOf(0)

    /**
     * The rows handed down by parent channels, in evaluation order. They only
     * apply while the channel inherits its ACLs, so they are not listed at all
     * when [ChanAclDraft.inheritAcls] is off (desktop `refillACL`).
     */
    fun inheritedRules(draft: ChanAclDraft): List<Int> =
        if (!draft.inheritAcls) emptyList() else (1 until inheritedCount(draft)).toList()

    /** The rows this channel owns — the only ones it can reorder or remove. */
    fun localRules(draft: ChanAclDraft): List<Int> =
        (inheritedCount(draft)..draft.rules.lastIndex).toList()

    /**
     * Desktop `ACLEditor::refillACL` item text: `@group` for a group target,
     * the cached name for a registered user, `#id` when the name is still
     * unknown — or the typed name while its lookup is in flight.
     */
    fun ruleLabel(draft: ChanAclDraft, userNames: AclUserNames, index: Int): String {
        val rule = draft.rules.getOrNull(index) ?: return ""
        if (rule.userId == ChanACL.UserId.ANY) return "@${rule.group}"
        return userLabel(draft, userNames, rule.userId)
    }

    /**
     * Desktop `ACLEditor::userName` for a user id anywhere the editor shows
     * one (an ACL row or a group member). An empty id means the entry targets a
     * group, not a user.
     */
    fun userLabel(draft: ChanAclDraft, userNames: AclUserNames, userId: Int): String {
        if (userId == ChanACL.UserId.ANY) return ""
        if (userId >= ChanACL.UserId.SUPERUSER) return userNames.displayName(userId)
        return draft.pendingNames[userId] ?: "#$userId"
    }

    /** Desktop `on_qcbACLInherit_clicked`. */
    fun setInheritAcls(draft: ChanAclDraft, checked: Boolean): ChanAclDraft =
        draft.copy(inheritAcls = checked)

    /** Desktop `on_qpbACLAdd_clicked`: a new row grants nothing and applies to `all`. */
    fun addRule(draft: ChanAclDraft): ChanAclDraft =
        draft.copy(
            rules = draft.rules + ChanAclRule(
                inherited = false,
                userId = ChanACL.UserId.ANY,
                group = ChanACL.Group.ALL,
                applyHere = true,
                applySubs = true,
                grant = ChanACL.NONE.toLong(),
                deny = ChanACL.NONE.toLong(),
            ),
        )

    /**
     * Desktop `on_qpbACLRemove_clicked`: inherited rows live in a parent
     * channel and can only be changed there.
     */
    fun removeRule(draft: ChanAclDraft, index: Int): ChanAclDraft {
        val rule = draft.rules.getOrNull(index) ?: return draft
        if (rule.inherited) return draft
        return draft.copy(rules = draft.rules.filterIndexed { i, _ -> i != index })
    }

    /**
     * Moves the row at [from] to [to]. Rows are evaluated top to bottom, so
     * moving one changes which entry wins. A local row can never leave the
     * block this channel owns — the desktop refuses to move one above an
     * inherited row and tells the user to duplicate the inherited entry
     * instead, and murmur would restore the parents' order on the next query
     * anyway.
     */
    fun moveRule(draft: ChanAclDraft, from: Int, to: Int): ChanAclDraft {
        val rules = draft.rules
        val rule = rules.getOrNull(from) ?: return draft
        if (rule.inherited) return draft
        val first = inheritedCount(draft)
        if (from < first) return draft
        val target = to.coerceIn(first, rules.lastIndex)
        if (target == from) return draft
        val next = rules.toMutableList()
        next.removeAt(from)
        next.add(target, rule)
        return draft.copy(rules = next)
    }

    /**
     * Desktop `on_qcbACLApplyHere_clicked` / `on_qcbACLApplySubs_clicked`:
     * unchecking one scope checks the other, because an entry that applies
     * nowhere would be silently dead.
     */
    fun setApplyHere(draft: ChanAclDraft, index: Int, checked: Boolean): ChanAclDraft =
        updateRule(draft, index) { rule ->
            rule.copy(
                applyHere = checked,
                applySubs = if (!checked && !rule.applySubs) true else rule.applySubs,
            )
        }

    fun setApplySubs(draft: ChanAclDraft, index: Int, checked: Boolean): ChanAclDraft =
        updateRule(draft, index) { rule ->
            rule.copy(
                applySubs = checked,
                applyHere = if (!checked && !rule.applyHere) true else rule.applyHere,
            )
        }

    /**
     * Desktop `on_qcbACLGroup_textActivated`: choosing a group drops the user
     * target (an entry applies to one or the other) and an empty name falls
     * back to `all`.
     */
    fun setGroup(draft: ChanAclDraft, index: Int, group: String): ChanAclDraft {
        val name = group.trim().ifEmpty { ChanACL.Group.ALL }
        return updateRule(draft, index) { it.copy(userId = ChanACL.UserId.ANY, group = name) }
    }

    /**
     * Desktop `on_qcbACLUser_textActivated`: an empty name goes back to the
     * group target, a cached name binds the registered id, an unknown one is
     * looked up and parked as a placeholder until the reply lands.
     */
    fun setUser(draft: ChanAclDraft, index: Int, name: String, userNames: AclUserNames): ChanAclDraft {
        val rule = draft.rules.getOrNull(index) ?: return draft
        if (rule.inherited) return draft
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            val cleared = draft.copy(
                rules = draft.rules.replace(index, rule.copy(userId = ChanACL.UserId.ANY)),
            )
            val group = cleared.rules[index].group
            return if (group.isEmpty()) setGroup(cleared, index, ChanACL.Group.ALL) else cleared
        }
        // Past the empty-name case above, the id cannot be missing: `bindName`
        // is the non-null half of `targetId`, whose only null is that empty
        // name. Calling it directly keeps the bound id non-nullable instead of
        // asserting an invariant that only this call site knows about.
        val (resolved, userId) = bindName(draft, trimmed, userNames)
        return resolved.copy(rules = resolved.rules.replace(index, rule.copy(userId = userId)))
    }

    /**
     * Desktop `ACLEditor::id`: turns a typed name into a registered id, asking
     * the server (and recording a placeholder) when the name is not in the
     * cache yet. Returns null for an empty name.
     */
    fun targetId(draft: ChanAclDraft, name: String, userNames: AclUserNames): Pair<ChanAclDraft, Int?> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return draft to null
        return bindName(draft, trimmed, userNames)
    }

    /** Desktop `ACLEnableCheck`: inherited rows are entirely read-only. */
    fun rowEditable(rule: ChanAclRule): Boolean = !rule.inherited

    /**
     * Desktop `ACLEnableCheck` per-checkbox gate: granting `Write` implies
     * every other privilege, so the rest of the row is locked while it is on.
     * `Speak` stays editable because Write does *not* imply it (SuperUser is
     * `All & ~(Speak | Whisper)`).
     */
    fun rulePermissionEnabled(rule: ChanAclRule, perm: Int): Boolean =
        !rule.inherited &&
            (perm == ChanACL.WRITE || !ChanACL.has(rule.grant, ChanACL.WRITE) || perm == ChanACL.SPEAK)

    /**
     * Desktop `ACLEditor::ACLPermissions_clicked`.
     *
     * [allow] says which of the two boxes was clicked and [checked] the state
     * being applied (Qt's `clicked` fires after the box flipped, so this
     * matches the desktop's read of `isChecked()`).
     */
    fun togglePermission(
        draft: ChanAclDraft,
        index: Int,
        perm: Int,
        allow: Boolean,
        checked: Boolean,
    ): ChanAclDraft {
        val rule = draft.rules.getOrNull(index) ?: return draft
        if (rule.inherited) return draft
        var grant = rule.grant
        var deny = rule.deny
        if (allow) {
            grant = setBit(grant, perm, checked)
        } else {
            deny = setBit(deny, perm, checked)
        }
        // "If a privilege is both allowed and denied, it is denied": the pair
        // is kept mutually exclusive, and the click decides which side wins.
        if (ChanACL.has(grant, perm) && ChanACL.has(deny, perm)) {
            if (allow) deny = setBit(deny, perm, false) else grant = setBit(grant, perm, false)
        }
        // Touching Enter also moves Listen, so a channel people may no longer
        // enter cannot still be listened into; both stay editable afterwards.
        // Unchecking Enter is not treated as a change of intent (desktop does
        // the same through `modifiedEnter`).
        if (perm == ChanACL.ENTER && checked) {
            if (ChanACL.has(deny, ChanACL.ENTER)) {
                grant = setBit(grant, ChanACL.LISTEN, false)
                deny = setBit(deny, ChanACL.LISTEN, true)
            } else {
                grant = setBit(grant, ChanACL.LISTEN, true)
                deny = setBit(deny, ChanACL.LISTEN, false)
            }
        }
        return draft.copy(rules = draft.rules.replace(index, rule.copy(grant = grant, deny = deny)))
    }

    /** The group named [name], or null. */
    fun group(draft: ChanAclDraft, name: String?): ChanAclGroup? =
        draft.groups.firstOrNull { it.name == name }

    /**
     * Desktop `on_qcbGroupList_textActivated`: a typed name creates a group
     * that starts out inheriting (and being inheritable to) its surroundings;
     * names are lower-cased the way murmur keys them.
     */
    fun addGroup(draft: ChanAclDraft, name: String): ChanAclDraft {
        val normalized = name.trim().lowercase()
        if (normalized.isEmpty() || draft.groups.any { it.name == normalized }) return draft
        return draft.copy(
            groups = draft.groups + ChanAclGroup(
                name = normalized,
                inherited = false,
                inherit = true,
                inheritable = true,
            ),
        )
    }

    /**
     * Desktop `on_qpbGroupRemove_clicked`: a group inherited from a parent
     * channel cannot be deleted here, so its local membership is cleared and it
     * is handed back to the parent (`Inherit` / `Inheritable` reset to the
     * inherited defaults).
     */
    fun removeGroup(draft: ChanAclDraft, name: String): ChanAclDraft {
        if (draft.groups.none { it.name == name }) return draft
        return draft.copy(
            groups = draft.groups.mapNotNull { group ->
                when {
                    group.name != name -> group
                    group.inherited -> group.copy(
                        inherit = true,
                        inheritable = true,
                        add = emptyList(),
                        remove = emptyList(),
                    )
                    else -> null
                }
            },
        )
    }

    /** Desktop `on_qcbGroupInherit_clicked`. */
    fun setGroupInherit(draft: ChanAclDraft, name: String, checked: Boolean): ChanAclDraft =
        updateGroup(draft, name) { it.copy(inherit = checked) }

    /** Desktop `on_qcbGroupInheritable_clicked`. */
    fun setGroupInheritable(draft: ChanAclDraft, name: String, checked: Boolean): ChanAclDraft =
        updateGroup(draft, name) { it.copy(inheritable = checked) }

    /** Desktop `on_qpbGroupAddAdd_clicked`: a member listed under `Members`. */
    fun addMember(draft: ChanAclDraft, name: String, member: Int): ChanAclDraft =
        updateGroup(draft, name) { group ->
            if (member in group.add) group else group.copy(add = group.add + member)
        }

    /** Desktop `on_qpbGroupAddRemove_clicked`. */
    fun removeMember(draft: ChanAclDraft, name: String, member: Int): ChanAclDraft =
        updateGroup(draft, name) { it.copy(add = it.add - member) }

    /**
     * Desktop `on_qpbGroupRemoveAdd_clicked` and
     * `on_qpbGroupInheritRemove_clicked`: both append to the group's `remove`
     * list, one from the picker and one from the inherited-members list.
     */
    fun excludeMember(draft: ChanAclDraft, name: String, member: Int): ChanAclDraft =
        updateGroup(draft, name) { group ->
            if (member in group.remove) group else group.copy(remove = group.remove + member)
        }

    /** Desktop `on_qpbGroupRemoveRemove_clicked`: drop an explicit exclusion. */
    fun removeExcludedMember(draft: ChanAclDraft, name: String, member: Int): ChanAclDraft =
        updateGroup(draft, name) { it.copy(remove = it.remove - member) }

    /** Names whose lookup has been sent but not answered yet. */
    fun pendingNames(draft: ChanAclDraft): Set<String> =
        referencedPlaceholders(draft).mapNotNull { draft.pendingNames[it] }.toSet()

    /**
     * Desktop `ACLEditor::returnQuery`: a placeholder id becomes the registered
     * id the server answered with. Lookups that no longer belong to any row are
     * dropped, so an abandoned query cannot come back and bind a stale entry.
     */
    fun resolveNames(draft: ChanAclDraft, userNames: AclUserNames): ChanAclDraft {
        val referenced = referencedPlaceholders(draft)
        if (referenced.isEmpty()) {
            return if (draft.pendingNames.isEmpty()) draft else draft.copy(pendingNames = emptyMap())
        }
        val resolved = mutableMapOf<Int, Int>()
        val remaining = mutableMapOf<Int, String>()
        for (placeholder in referenced) {
            val name = draft.pendingNames[placeholder] ?: continue
            val id = userNames.idOf(name)
            if (id != null) resolved[placeholder] = id else remaining[placeholder] = name
        }
        if (resolved.isEmpty() && remaining == draft.pendingNames) return draft
        fun remap(ids: List<Int>): List<Int> = ids.map { resolved[it] ?: it }
        return draft.copy(
            rules = draft.rules.map { rule ->
                if (resolved.containsKey(rule.userId)) {
                    rule.copy(userId = resolved.getValue(rule.userId))
                } else {
                    rule
                }
            },
            groups = draft.groups.map { group ->
                if (group.add.any(resolved::containsKey) || group.remove.any(resolved::containsKey)) {
                    group.copy(add = remap(group.add), remove = remap(group.remove))
                } else {
                    group
                }
            },
            pendingNames = remaining,
        )
    }

    /**
     * The name cache the editor resolves ids against. The session fills it from
     * the `QueryUsers` reply murmur sends after every ACL query; seeding it with
     * the registered users currently online is what makes a user pickable
     * before being mentioned in any ACL (desktop does the same from
     * `ClientUser::c_qmUsers`).
     */
    fun candidates(userNames: AclUserNames, onlineUsers: List<User>): AclUserNames {
        val registered = onlineUsers.filter { it.userId >= ChanACL.UserId.SUPERUSER }
        return userNames.merge(registered.map { it.userId }, registered.map { it.name })
    }

    private fun updateRule(
        draft: ChanAclDraft,
        index: Int,
        transform: (ChanAclRule) -> ChanAclRule,
    ): ChanAclDraft {
        val rule = draft.rules.getOrNull(index) ?: return draft
        if (rule.inherited) return draft
        return draft.copy(rules = draft.rules.replace(index, transform(rule)))
    }

    private fun updateGroup(
        draft: ChanAclDraft,
        name: String,
        transform: (ChanAclGroup) -> ChanAclGroup,
    ): ChanAclDraft {
        if (draft.groups.none { it.name == name }) return draft
        return draft.copy(
            groups = draft.groups.map { if (it.name == name) transform(it) else it },
        )
    }

    private fun bindName(
        draft: ChanAclDraft,
        name: String,
        userNames: AclUserNames,
    ): Pair<ChanAclDraft, Int> {
        userNames.idOf(name)?.let { return draft to it }
        val existing = draft.pendingNames.entries
            .firstOrNull { it.value.equals(name, ignoreCase = true) }
        if (existing != null) return draft to existing.key
        val placeholder = nextPlaceholderId(draft)
        return draft.copy(pendingNames = draft.pendingNames + (placeholder to name)) to placeholder
    }

    /**
     * Desktop starts its unknown-id counter at -2 and counts down, keeping
     * placeholders clear of `-1` (the "this entry targets a group" marker).
     */
    private fun nextPlaceholderId(draft: ChanAclDraft): Int {
        var id = ChanACL.UserId.ANY - 1
        while (draft.pendingNames.containsKey(id)) id--
        return id
    }

    private fun referencedPlaceholders(draft: ChanAclDraft): Set<Int> {
        val ids = mutableSetOf<Int>()
        for (rule in draft.rules) {
            if (rule.userId < ChanACL.UserId.ANY) ids += rule.userId
        }
        for (group in draft.groups) {
            group.add.filterTo(ids) { it < ChanACL.UserId.ANY }
            group.remove.filterTo(ids) { it < ChanACL.UserId.ANY }
        }
        return ids
    }

    private fun setBit(mask: Long, bit: Int, on: Boolean): Long {
        val value = bit.toLong()
        return if (on) mask or value else mask and value.inv()
    }

    private fun <T> List<T>.replace(index: Int, value: T): List<T> =
        mapIndexed { i, item -> if (i == index) value else item }
}
