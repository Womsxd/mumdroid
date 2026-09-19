package dev.woms.mumdroid.ui.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclDraft
import dev.woms.mumdroid.core.net.ChanAclRule
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.core.net.ChannelAclEdit
import kotlin.math.roundToInt

/**
 * One permission bit with its label and explanation, in the desktop
 * `ACLEditor` row order (`ChanACL::permName` / `whatsThis`).
 */
private data class AclPermission(
    val bit: Int,
    @StringRes val name: Int,
    @StringRes val description: Int,
)

private val ACL_PERMISSIONS = listOf(
    AclPermission(ChanACL.WRITE, R.string.acl_perm_write, R.string.acl_perm_write_desc),
    AclPermission(ChanACL.TRAVERSE, R.string.acl_perm_traverse, R.string.acl_perm_traverse_desc),
    AclPermission(ChanACL.ENTER, R.string.acl_perm_enter, R.string.acl_perm_enter_desc),
    AclPermission(ChanACL.SPEAK, R.string.acl_perm_speak, R.string.acl_perm_speak_desc),
    AclPermission(ChanACL.MUTE_DEAFEN, R.string.acl_perm_mute_deafen, R.string.acl_perm_mute_deafen_desc),
    AclPermission(ChanACL.MOVE, R.string.acl_perm_move, R.string.acl_perm_move_desc),
    AclPermission(ChanACL.MAKE_CHANNEL, R.string.acl_perm_make_channel, R.string.acl_perm_make_channel_desc),
    AclPermission(ChanACL.LINK_CHANNEL, R.string.acl_perm_link_channel, R.string.acl_perm_link_channel_desc),
    AclPermission(ChanACL.WHISPER, R.string.acl_perm_whisper, R.string.acl_perm_whisper_desc),
    AclPermission(ChanACL.TEXT_MESSAGE, R.string.acl_perm_text_message, R.string.acl_perm_text_message_desc),
    AclPermission(
        ChanACL.MAKE_TEMP_CHANNEL,
        R.string.acl_perm_make_temp_channel,
        R.string.acl_perm_make_temp_channel_desc,
    ),
    AclPermission(ChanACL.LISTEN, R.string.acl_perm_listen, R.string.acl_perm_listen_desc),
    AclPermission(ChanACL.KICK, R.string.acl_perm_kick, R.string.acl_perm_kick_desc),
    AclPermission(ChanACL.BAN, R.string.acl_perm_ban, R.string.acl_perm_ban_desc),
    AclPermission(
        ChanACL.RESET_USER_CONTENT,
        R.string.acl_perm_reset_user_content,
        R.string.acl_perm_reset_user_content_desc,
    ),
    AclPermission(ChanACL.REGISTER, R.string.acl_perm_register, R.string.acl_perm_register_desc),
    AclPermission(ChanACL.SELF_REGISTER, R.string.acl_perm_self_register, R.string.acl_perm_self_register_desc),
)

private const val TAB_ACL = 0
private const val TAB_GROUPS = 1

/** Width of each deny/allow column, so the header stays over the boxes. */
private val PERMISSION_COLUMN = 48.dp

/** Fixed height of an entries-list row, so a drag maps cleanly onto a distance. */
private val ENTRY_ROW_HEIGHT = 72.dp

/** Leading slot of an entry row: the drag handle, or its space on a read-only row. */
private val ENTRY_HANDLE_WIDTH = 40.dp

/** Trailing slot of an entry row: the delete button, or its space on a read-only row. */
private val ENTRY_ACTION_WIDTH = 48.dp

/** Permission names listed per entry before the summary is cut short. */
private const val SUMMARY_NAMES = 4

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
                                onDelete = { index ->
                                    edit { ChannelAclEdit.removeRule(it, index) }
                                },
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

/**
 * The entries list: every rule the channel evaluates, the channel's own inherit
 * switch above them, and a way into each rule's editor.
 *
 * A `Column` rather than a `LazyColumn`, so that every row is composed and a
 * drag can be measured against the row height — the same trade-off, and the
 * same drag handling, as the output-device order list.
 */
@Composable
private fun AclEntryList(
    draft: ChanAclDraft,
    userNames: AclUserNames,
    permissions: List<AclPermission>,
    onOpen: (Int) -> Unit,
    onEdit: ((ChanAclDraft) -> ChanAclDraft) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val names = permissions.map { it.bit to stringResource(it.name) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.acl_active),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        ChannelAclEdit.visibleRules(draft).forEach { index ->
            AclEntryRow(
                draft = draft,
                index = index,
                userNames = userNames,
                names = names,
                onOpen = onOpen,
                onMove = onMove,
                onDelete = onDelete,
            )
        }
        // Room for the add button, so the last entry stays reachable.
        Box(Modifier.padding(bottom = 88.dp))
    }
}

/**
 * One row of the list: a handle to reorder it, its label and summary, and the
 * delete action. The handle only exists on rows this channel owns — an
 * inherited row belongs to a parent channel, so it cannot be moved or removed
 * here (desktop `numInheritACL`, `ACLEnableCheck`).
 *
 * The drag slides the row under the finger and applies the new position on
 * release: unlike the output-device list, the rows here have no stable identity
 * to key the item on, and shuffling them live would tear down the very node
 * holding the gesture.
 */
@Composable
private fun AclEntryRow(
    draft: ChanAclDraft,
    index: Int,
    userNames: AclUserNames,
    names: List<Pair<Int, String>>,
    onOpen: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val rule = draft.rules[index]
    val editable = ChannelAclEdit.rowEditable(rule)
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ENTRY_ROW_HEIGHT.toPx() }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ENTRY_ROW_HEIGHT)
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, if (dragging) dragOffsetY.roundToInt() else 0) }
            .background(
                if (dragging) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The grab area is the whole leading slot, not just the glyph, so the
        // handle is as easy to catch as a list row's touch target.
        Box(
            modifier = Modifier
                .width(ENTRY_HANDLE_WIDTH)
                .fillMaxHeight()
                .then(
                    if (editable) {
                        Modifier.pointerInput(index) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val delta = (dragOffsetY / rowHeightPx).roundToInt()
                                    dragging = false
                                    dragOffsetY = 0f
                                    if (delta != 0) {
                                        onMove(index, index + delta)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetY += amount.y
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (editable) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.acl_reorder_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { onOpen(index) }
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                ChannelAclEdit.ruleLabel(draft, userNames, index),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (rule.inherited) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AclEntrySummary(rule, names)
        }
        Box(
            modifier = Modifier.width(ENTRY_ACTION_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            if (editable) {
                IconButton(onClick = { onDelete(index) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.acl_remove_entry),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

/**
 * What an entry grants and denies, so the list can be read without opening
 * every row in turn — the desktop prints the same summary from
 * `ChanACL::operator QString`, shortened here to fit a list row.
 */
@Composable
private fun AclEntrySummary(rule: ChanAclRule, names: List<Pair<Int, String>>) {
    val separator = stringResource(R.string.acl_list_separator)
    val allowed = maskNames(rule.grant, names)
    val denied = maskNames(rule.deny, names)
    val summary = listOfNotNull(
        allowed.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.acl_entry_allowed, formatNames(it, separator))
        },
        denied.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.acl_entry_denied, formatNames(it, separator))
        },
    ).joinToString(" · ")
    if (summary.isEmpty()) return
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One entry: its scope, its target and its permissions. Ordering and deletion
 * are the entries list's business — it shows the whole order at once, and the
 * drag there says where the entry is going.
 */
@Composable
private fun AclEntryEditor(
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

/** A labelled text field with a dropdown of known names, committed on Done. */
@Composable
internal fun AclNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    enabled: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            supportingText = supporting?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            trailingIcon = {
                IconButton(onClick = { open = true }, enabled = enabled && suggestions.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.acl_query_user),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        open = false
                        onValueChange(suggestion)
                        onCommit()
                    },
                )
            }
        }
    }
}

/**
 * One titled block of the editor: the same shape `BanEditScreen` gives its
 * sections, so the two administration screens read alike. [action] sits on the
 * title line where a section needs one (the group delete).
 */
@Composable
internal fun AclSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        content()
    }
}

private fun maskNames(mask: Long, names: List<Pair<Int, String>>): List<String> =
    names.filter { ChanACL.has(mask, it.first) }.map { it.second }

private fun formatNames(names: List<String>, separator: String): String {
    val shown = names.take(SUMMARY_NAMES).joinToString(separator)
    return if (names.size > SUMMARY_NAMES) "$shown…" else shown
}

@Composable
private fun PermissionHelpDialog(permission: AclPermission, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(permission.name)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(permission.description))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun UnresolvedSaveDialog(
    names: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.acl_unresolved_title)) },
        text = {
            Text(
                stringResource(
                    R.string.acl_unresolved_confirm,
                    names.joinToString(separator = "\n") { "· $it" },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
