package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanAclDraft
import dev.woms.mumdroid.core.model.ChannelAclEdit

/**
 * The entries list, split by where each rule comes from: what murmur grants
 * when nothing else applies, what parent channels hand down, and what this
 * channel owns.
 *
 * The blocks are in evaluation order — murmur walks from the root down and the
 * last matching entry wins — so the channel's own rules, the only editable
 * ones, sit at the bottom, right where the add button appends to.
 *
 * A `Column` rather than a `LazyColumn`, so that every row is composed and a
 * drag can be measured against the row height — the same trade-off, and the
 * same drag handling, as the output-device order list.
 */
@Composable
internal fun AclEntryList(
    draft: ChanAclDraft,
    userNames: AclUserNames,
    permissions: List<AclPermission>,
    onOpen: (Int) -> Unit,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
) {
    val names = permissions.map { it.bit to stringResource(it.name) }
    val rows: @Composable (List<Int>) -> Unit = { indices ->
        AclRows(
            indices = indices,
            draft = draft,
            userNames = userNames,
            names = names,
            onOpen = onOpen,
            onMove = onMove,
            onRequestDelete = onRequestDelete,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp),
    ) {
        AclBlock(stringResource(R.string.acl_section_default)) {
            rows(ChannelAclEdit.defaultRules(draft))
        }
        AclBlock(stringResource(R.string.acl_section_inherited)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.acl_inherit_parent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = draft.inheritAcls,
                    onCheckedChange = { checked ->
                        onEdit { ChannelAclEdit.setInheritAcls(it, checked) }
                    },
                )
            }
            Text(
                stringResource(R.string.acl_inherited_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            rows(ChannelAclEdit.inheritedRules(draft))
        }
        AclBlock(stringResource(R.string.acl_section_local)) {
            Text(
                stringResource(R.string.acl_list_gestures),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            rows(ChannelAclEdit.localRules(draft))
        }
        // Room for the add button, so the last entry stays reachable.
        Box(Modifier.padding(bottom = 88.dp))
    }
}

/** The rows of one block, in the order they are evaluated. */
@Composable
private fun AclRows(
    indices: List<Int>,
    draft: ChanAclDraft,
    userNames: AclUserNames,
    names: List<Pair<Int, String>>,
    onOpen: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
) {
    indices.forEach { index ->
        AclEntryRow(
            draft = draft,
            index = index,
            userNames = userNames,
            names = names,
            onOpen = onOpen,
            onMove = onMove,
            onRequestDelete = onRequestDelete,
        )
    }
}

/**
 * One titled block of the entries list. The rows sit full-bleed (they carry
 * their own dividers), so this cannot use the editor's [AclSection], which
 * spaces its content for form fields.
 */
@Composable
private fun AclBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        content()
    }
}
