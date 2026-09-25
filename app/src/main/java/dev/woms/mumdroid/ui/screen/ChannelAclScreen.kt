package dev.woms.mumdroid.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclDraft
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.model.ChannelAclEdit
import dev.woms.mumdroid.core.model.User

private const val TAB_ACL = 0
private const val TAB_GROUPS = 1

/**
 * The channel ACL editor: the desktop `ACLEditor`'s ACL and Groups tabs as a
 * full screen, reached from a channel's long-press menu.
 *
 * The reply is read once into a local draft ([ChanAclDraft]) and only sent back
 * from Save, exactly like the desktop dialog holds `qlACLs` / `qlGroups` and
 * writes them from `accept()`.
 *
 * The ACL tab is a master/detail pair instead of the desktop's side-by-side
 * split: this screen shows the entries, and tapping one swaps the content for
 * that entry's scope, target and permissions. Desktop fits both halves next to
 * each other, but a phone cannot — sharing one column of scrolling between
 * list and editor turns every look at another entry into a scroll round trip.
 *
 * The pieces this screen wires together live beside it: [AclEntryList] and
 * [AclEntryRow] for the list, [AclEntryEditor] for one entry,
 * [PermissionHelpDialog] / [DeleteEntryDialog] / [UnresolvedSaveDialog] for the
 * confirmations, [AclSection] and [AclNameField] for what all of them are built
 * from, and [ChannelAclGroupsTab] for the other tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelAclScreen(
    channelId: Int,
    channelName: String,
    snapshot: ChanAclSnapshot?,
    userNames: AclUserNames,
    onlineUsers: List<User>,
    supportsListen: Boolean,
    supportsResetUserContent: Boolean,
    onBack: () -> Unit,
    onQueryUsersByName: (List<String>) -> Unit,
    onSave: (ChanAclSnapshot) -> Unit,
) {
    var draft by remember(channelId) { mutableStateOf<ChanAclDraft?>(null) }
    var tab by remember { mutableIntStateOf(TAB_ACL) }
    /** Entry being edited, or null while the entries list is showing. */
    var editing by remember { mutableStateOf<Int?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf<AclPermission?>(null) }
    var pendingSave by remember { mutableStateOf(false) }
    /** Entry a swipe asked to remove, waiting for the confirmation. */
    var pendingDelete by remember { mutableStateOf<Int?>(null) }

    val names = remember(userNames, onlineUsers) { ChannelAclEdit.candidates(userNames, onlineUsers) }
    val seed = snapshot?.takeIf { it.channelId == channelId }

    LaunchedEffect(seed) {
        if (seed != null && draft == null) {
            draft = ChannelAclEdit.fromSnapshot(seed)
            editing = null
            selectedGroup = seed.groups.firstOrNull()?.name
        }
    }

    // The QueryUsers reply that resolves the placeholders arrives after the
    // ACL itself, so the draft keeps absorbing name answers while it is open.
    LaunchedEffect(names) {
        val current = draft ?: return@LaunchedEffect
        draft = ChannelAclEdit.resolveNames(current, names)
    }

    val pendingNames = draft?.let(ChannelAclEdit::pendingNames).orEmpty()
    val queried = remember(channelId) { mutableSetOf<String>() }
    LaunchedEffect(pendingNames) {
        val unknown = pendingNames.filterNot(queried::contains)
        if (unknown.isNotEmpty()) {
            queried += unknown
            onQueryUsersByName(unknown)
        }
    }

    val permissions = remember(channelId, supportsListen, supportsResetUserContent) {
        val bits = if (channelId == ChanACL.ChannelId.ROOT) {
            ChanACL.EDITOR_PERMISSIONS_ROOT
        } else {
            ChanACL.EDITOR_PERMISSIONS_CHANNEL
        }
        ACL_PERMISSIONS.filter { permission ->
            permission.bit in bits &&
                (permission.bit != ChanACL.LISTEN || supportsListen) &&
                (permission.bit != ChanACL.RESET_USER_CONTENT || supportsResetUserContent)
        }
    }

    val current = draft
    fun edit(transform: (ChanAclDraft) -> ChanAclDraft) {
        draft = draft?.let(transform)
    }

    fun save() {
        val ready = draft ?: return
        if (ChannelAclEdit.pendingNames(ready).isEmpty()) {
            onSave(ChannelAclEdit.toSnapshot(ready))
        } else {
            pendingSave = true
        }
    }

    // Back leaves the entry editor first, then the whole screen.
    BackHandler { if (editing != null) editing = null else onBack() }

    Scaffold(
        topBar = {
            val editingIndex = editing
            TopAppBar(
                title = {
                    Text(
                        if (editingIndex == null || current == null) {
                            stringResource(R.string.acl_editor_title, channelName.ifEmpty { "#$channelId" })
                        } else {
                            stringResource(
                                R.string.acl_entry_title,
                                ChannelAclEdit.ruleLabel(current, names, editingIndex),
                            )
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (editingIndex != null) editing = null else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { save() },
                        enabled = current != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
        floatingActionButton = {
            // Adding belongs to the entries list, not to either tab and not to
            // the single entry being edited. Same button as the server list.
            if (editing == null && tab == TAB_ACL && current != null) {
                FloatingActionButton(
                    onClick = {
                        // The new row is appended, so it lands at the old size.
                        val last = current.rules.size
                        edit(ChannelAclEdit::addRule)
                        editing = last
                    },
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.acl_add_entry),
                    )
                }
            }
        },
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.acl_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            return@Scaffold
        }

        // `imePadding` on the shell so the text fields of either level stay
        // above the keyboard, and the content takes the remaining height so a
        // long draft cannot push the app bar off the top.
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            val editingIndex = editing
            when {
                editingIndex == null -> {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == TAB_ACL,
                            onClick = { tab = TAB_ACL },
                            text = { Text(stringResource(R.string.acl_tab_acl)) },
                        )
                        Tab(
                            selected = tab == TAB_GROUPS,
                            onClick = { tab = TAB_GROUPS },
                            text = { Text(stringResource(R.string.acl_tab_groups)) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (tab == TAB_ACL) {
                            AclEntryList(
                                draft = current,
                                userNames = names,
                                permissions = permissions,
                                onOpen = { editing = it },
                                onEdit = ::edit,
                                onMove = { from, to ->
                                    edit { ChannelAclEdit.moveRule(it, from, to) }
                                },
                                onRequestDelete = { pendingDelete = it },
                            )
                        } else {
                            ChannelAclGroupsTab(
                                draft = current,
                                selectedGroup = selectedGroup,
                                userNames = names,
                                onSelectGroup = { selectedGroup = it },
                                onEdit = ::edit,
                            )
                        }
                    }
                }

                else -> Box(modifier = Modifier.weight(1f)) {
                    AclEntryEditor(
                        draft = current,
                        index = editingIndex,
                        userNames = names,
                        permissions = permissions,
                        onEdit = ::edit,
                        onHelp = { help = it },
                    )
                }
            }
        }
    }

    help?.let { permission ->
        PermissionHelpDialog(permission = permission, onDismiss = { help = null })
    }

    val deleting = pendingDelete
    if (deleting != null && current != null) {
        DeleteEntryDialog(
            label = ChannelAclEdit.ruleLabel(current, names, deleting),
            onConfirm = {
                edit { ChannelAclEdit.removeRule(it, deleting) }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (pendingSave && current != null) {
        UnresolvedSaveDialog(
            names = ChannelAclEdit.pendingNames(current).sorted(),
            onConfirm = {
                pendingSave = false
                onSave(ChannelAclEdit.toSnapshot(current))
            },
            onDismiss = { pendingSave = false },
        )
    }
}
