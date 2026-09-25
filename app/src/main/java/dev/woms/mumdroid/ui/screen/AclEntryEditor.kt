package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclDraft
import dev.woms.mumdroid.core.model.ChanAclRule
import dev.woms.mumdroid.core.model.ChannelAclEdit

/** Width of each deny/allow column, so the header stays over the boxes. */
private val PERMISSION_COLUMN = 48.dp

/**
 * One entry: its scope, its target and its permissions. Ordering and deletion
 * are the entries list's business — it shows the whole order at once, and the
 * drag there says where the entry is going.
 */
@Composable
internal fun AclEntryEditor(
    draft: ChanAclDraft,
    index: Int,
    userNames: AclUserNames,
    permissions: List<AclPermission>,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
    onHelp: (AclPermission) -> Unit,
) {
    val rule = draft.rules.getOrNull(index)
    val editable = rule != null && ChannelAclEdit.rowEditable(rule)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!editable) {
            Text(
                stringResource(R.string.acl_entry_inherited),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AclSection(stringResource(R.string.acl_context)) {
            AclScope(rule, index, onEdit)
        }
        AclSection(stringResource(R.string.acl_target)) {
            AclTarget(rule, draft, index, userNames, onEdit)
        }
        AclSection(stringResource(R.string.acl_permissions)) {
            PermissionMatrix(index, rule, permissions, onEdit, onHelp)
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun AclScope(
    rule: ChanAclRule?,
    index: Int,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
) {
    val editable = rule != null && ChannelAclEdit.rowEditable(rule)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.acl_apply_here),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = rule?.applyHere == true,
            enabled = editable,
            onCheckedChange = { checked -> onEdit { ChannelAclEdit.setApplyHere(it, index, checked) } },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.acl_apply_subs),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = rule?.applySubs == true,
            enabled = editable,
            onCheckedChange = { checked -> onEdit { ChannelAclEdit.setApplySubs(it, index, checked) } },
        )
    }
}

@Composable
private fun AclTarget(
    rule: ChanAclRule?,
    draft: ChanAclDraft,
    index: Int,
    userNames: AclUserNames,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
) {
    val editable = rule != null && ChannelAclEdit.rowEditable(rule)
    val groupSuggestions = remember(draft.groups) {
        (ChanACL.Group.EDITOR_PRESETS + draft.groups.map { it.name })
            .distinct()
            .sorted()
    }
    val userSuggestions = remember(userNames) {
        userNames.idToName.values.distinct().sorted()
    }

    // A row targets either a group or a user, so the group field only shows the
    // group while that is what the row points at.
    val groupTarget = rule != null && rule.userId == ChanACL.UserId.ANY
    var groupText by remember(index, groupTarget) {
        mutableStateOf(if (groupTarget) rule.group else "")
    }
    val pendingName = rule?.userId?.takeIf { it < ChanACL.UserId.ANY }?.let { draft.pendingNames[it] }
    val userLabel = if (rule != null && rule.userId != ChanACL.UserId.ANY) {
        ChannelAclEdit.ruleLabel(draft, userNames, index)
    } else {
        ""
    }
    var userText by remember(index, userLabel) { mutableStateOf(userLabel) }

    Text(
        stringResource(R.string.acl_target_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AclNameField(
        value = groupText,
        onValueChange = { text ->
            groupText = text
            // An empty field would make the row apply to nobody, so the `all`
            // fallback only happens once the edit is committed.
            if (text.isNotBlank()) {
                onEdit { ChannelAclEdit.setGroup(it, index, text) }
            }
        },
        label = stringResource(R.string.acl_group),
        suggestions = groupSuggestions,
        enabled = editable,
        onCommit = {
            val text = groupText
            onEdit { ChannelAclEdit.setGroup(it, index, text) }
            groupText = text.trim().ifEmpty { ChanACL.Group.ALL }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    AclNameField(
        value = userText,
        onValueChange = { userText = it },
        label = stringResource(R.string.acl_user),
        suggestions = userSuggestions,
        enabled = editable,
        onCommit = {
            // Unknown names go to the server as a QueryUsers; the pending
            // placeholder keeps the typed name visible until it answers.
            onEdit { ChannelAclEdit.setUser(it, index, userText, userNames) }
        },
        supporting = pendingName?.let { stringResource(R.string.acl_pending_user, it) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The permission matrix: one row per bit, with the deny and allow boxes in
 * fixed columns. A row is locked while it grants `Write` (desktop
 * `ACLEnableCheck`); the row itself opens the explanation, so the width an info
 * button would take on every row stays with the label.
 */
@Composable
private fun PermissionMatrix(
    index: Int,
    rule: ChanAclRule?,
    permissions: List<AclPermission>,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
    onHelp: (AclPermission) -> Unit,
) {
    if (rule == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        PermissionColumnHeader(stringResource(R.string.acl_deny))
        PermissionColumnHeader(stringResource(R.string.acl_allow))
    }
    permissions.forEach { permission ->
        val enabled = ChannelAclEdit.rulePermissionEnabled(rule, permission.bit)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onHelp(permission) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(permission.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.acl_permission_help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(14.dp),
                )
            }
            Box(Modifier.width(PERMISSION_COLUMN), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked = ChanACL.has(rule.deny, permission.bit),
                    onCheckedChange = { checked ->
                        onEdit {
                            ChannelAclEdit.togglePermission(it, index, permission.bit, false, checked)
                        }
                    },
                    enabled = enabled,
                )
            }
            Box(Modifier.width(PERMISSION_COLUMN), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked = ChanACL.has(rule.grant, permission.bit),
                    onCheckedChange = { checked ->
                        onEdit {
                            ChannelAclEdit.togglePermission(it, index, permission.bit, true, checked)
                        }
                    },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun PermissionColumnHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(PERMISSION_COLUMN),
    )
}
