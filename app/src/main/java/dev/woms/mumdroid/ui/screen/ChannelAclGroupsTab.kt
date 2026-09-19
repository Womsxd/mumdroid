package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclDraft
import dev.woms.mumdroid.core.net.ChanAclGroup
import dev.woms.mumdroid.core.net.ChannelAclEdit

/**
 * The desktop `ACLEditor` Groups tab: the channel's groups and their inherit
 * flags, then the three membership lists that make a group up — members added
 * here, members excluded from what a parent channel grants, and the members
 * inherited from that parent.
 *
 * The group list is the picker for the sections under it, so it is capped and
 * scrolls inside itself, the same way the ACL tab's entry list does.
 *
 * `Excluded members` and `Inherited members` only carry meaning while the group
 * inherits its members from above, so they are disabled when it does not
 * (desktop `groupEnableCheck`).
 */
@Composable
internal fun ChannelAclGroupsTab(
    draft: ChanAclDraft,
    selectedGroup: String?,
    userNames: AclUserNames,
    onSelectGroup: (String?) -> Unit,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
) {
    val group = ChannelAclEdit.group(draft, selectedGroup)
    var newGroup by remember { mutableStateOf("") }
    val groupNames = remember(draft.groups) { draft.groups.map { it.name }.sorted() }
    val memberNames = remember(userNames) { userNames.idToName.values.distinct().sorted() }

    /**
     * Resolves a typed member name and applies [add] with the id it bound to.
     * An unknown name is registered as a lookup placeholder, so the draft that
     * carries it has to be the one the add is applied to — otherwise the row
     * would lose the name it is waiting for.
     */
    fun linkMember(name: String, add: (ChanAclDraft, Int) -> ChanAclDraft) {
        val (named, member) = ChannelAclEdit.targetId(draft, name, userNames)
        onEdit { if (member == null) named else add(named, member) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AclSection(
            title = stringResource(R.string.acl_group),
            action = {
                IconButton(
                    onClick = {
                        val name = group?.name ?: return@IconButton
                        onEdit { ChannelAclEdit.removeGroup(it, name) }
                        onSelectGroup(null)
                    },
                    enabled = group != null,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.acl_group_remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {
            AclNameField(
                value = newGroup,
                onValueChange = { newGroup = it },
                label = stringResource(R.string.acl_group_new_hint),
                suggestions = groupNames,
                enabled = true,
                onCommit = {
                    val name = newGroup.trim()
                    if (name.isNotEmpty()) {
                        onEdit { ChannelAclEdit.addGroup(it, name) }
                        onSelectGroup(name.lowercase())
                        newGroup = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.groups.isEmpty()) {
                Text(
                    stringResource(R.string.acl_groups_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = GROUP_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                draft.groups.forEach { row ->
                    GroupRow(
                        group = row,
                        selected = row.name == selectedGroup,
                        onSelect = { onSelectGroup(row.name) },
                    )
                }
            }
            if (group != null) GroupFlags(group, onEdit)
        }

        if (group != null) {
            val inherits = group.inherit
            AclSection(stringResource(R.string.acl_group_members)) {
                group.add.forEach { member ->
                    MemberRow(
                        label = ChannelAclEdit.userLabel(draft, userNames, member),
                        actionDescription = stringResource(R.string.acl_group_remove_member),
                        onAction = { onEdit { ChannelAclEdit.removeMember(it, group.name, member) } },
                    )
                }
                MemberAdder(
                    label = stringResource(R.string.acl_query_user_hint),
                    suggestions = memberNames,
                    enabled = true,
                ) { name ->
                    linkMember(name) { current, member -> ChannelAclEdit.addMember(current, group.name, member) }
                }
            }

            AclSection(stringResource(R.string.acl_group_excluded)) {
                group.remove.forEach { member ->
                    MemberRow(
                        label = ChannelAclEdit.userLabel(draft, userNames, member),
                        actionDescription = stringResource(R.string.acl_group_remove_member),
                        enabled = inherits,
                        onAction = { onEdit { ChannelAclEdit.removeExcludedMember(it, group.name, member) } },
                    )
                }
                MemberAdder(
                    label = stringResource(R.string.acl_query_user_hint),
                    suggestions = memberNames,
                    enabled = inherits,
                ) { name ->
                    linkMember(name) { current, member ->
                        ChannelAclEdit.excludeMember(current, group.name, member)
                    }
                }
            }

            AclSection(stringResource(R.string.acl_group_inherited_members)) {
                group.inheritedMembers.forEach { member ->
                    MemberRow(
                        label = ChannelAclEdit.userLabel(draft, userNames, member),
                        actionDescription = stringResource(R.string.acl_group_exclude),
                        actionLabel = stringResource(R.string.acl_group_exclude),
                        enabled = inherits,
                        onAction = { onEdit { ChannelAclEdit.excludeMember(it, group.name, member) } },
                    )
                }
            }
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun GroupRow(
    group: ChanAclGroup,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.name,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = if (group.inherited) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (group.inherited) {
            Text(
                stringResource(R.string.acl_group_inherited),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupFlags(
    group: ChanAclGroup,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.acl_group_inherit),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = group.inherit,
            onCheckedChange = { checked ->
                onEdit { ChannelAclEdit.setGroupInherit(it, group.name, checked) }
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.acl_group_inheritable),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = group.inheritable,
            onCheckedChange = { checked ->
                onEdit { ChannelAclEdit.setGroupInheritable(it, group.name, checked) }
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.acl_group_inherited),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Reported by the server; there is nothing to toggle here.
        Checkbox(checked = group.inherited, onCheckedChange = null, enabled = false)
    }
}

@Composable
private fun MemberRow(
    label: String,
    actionDescription: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    actionLabel: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        } else {
            IconButton(onClick = onAction, enabled = enabled) {
                Icon(
                    Icons.Filled.RemoveCircleOutline,
                    contentDescription = actionDescription,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** A name field that hands the typed name over and clears itself. */
@Composable
private fun MemberAdder(
    label: String,
    suggestions: List<String>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AclNameField(
        value = text,
        onValueChange = { text = it },
        label = label,
        suggestions = suggestions,
        enabled = enabled,
        onCommit = {
            if (text.isNotBlank()) {
                onAdd(text)
                text = ""
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Same cap as the ACL tab's entry list: this list is a picker, not the page. */
private val GROUP_LIST_MAX_HEIGHT = 200.dp
