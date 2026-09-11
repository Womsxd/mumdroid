package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel

/** One channel row: its label, its context menu and its nested children. */
@Composable
internal fun ChannelNode(
    channel: Channel,
    indent: Int,
    collapsedIds: Set<Int>,
    onToggleCollapsed: (Int) -> Unit,
    actions: ChannelTreeActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var shoutDialog by remember { mutableStateOf(false) }
    var sendDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val flags = ChannelNodeVisibility.of(
        channel = channel,
        collapsedIds = collapsedIds,
        localChannelId = actions.localChannelId,
        homeAllLinks = actions.homeAllLinks,
        homeDirectLinks = actions.homeDirectLinks,
        listeningChannels = actions.listeningChannels,
        activeShoutChannelId = actions.activeShoutChannelId,
        supportsChannelListen = actions.supportsChannelListen(),
        mayWhisper = actions.mayWhisper,
        canListen = actions.canListen,
        canTextMessage = actions.canTextMessage,
        canAddChannel = actions.canAddChannel,
        canMakePermanentChannel = actions.canMakePermanentChannel,
        canWriteChannel = actions.canWriteChannel,
        canLinkChannel = actions.canLinkChannel,
    )
    val linkMenu = ChannelNodeVisibility.linkMenu(
        channel = channel,
        localChannelId = actions.localChannelId,
        homeAllLinks = actions.homeAllLinks,
        homeDirectLinks = actions.homeDirectLinks,
        canLinkChannel = actions.canLinkChannel,
    )

    LaunchedEffect(menuOpen, actions.permissionEpoch) {
        if (menuOpen) {
            actions.onQueryChannelPermissions(channel.id)
            actions.onQueryChannelPermissions(actions.localChannelId)
        }
    }
    LaunchedEffect(editDialog) {
        if (editDialog) {
            actions.onRequestChannelDescription(channel.id)
            actions.onRequestChannelAcl(channel.id)
        }
    }

    Column(modifier = Modifier.padding(start = (indent * 16).dp)) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (flags.canCollapse) {
                    IconButton(
                        onClick = { onToggleCollapsed(channel.id) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (flags.collapsed) {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = stringResource(
                                if (flags.collapsed) R.string.expand_channel
                                else R.string.collapse_channel,
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (channel.id != 0) {
                    Spacer(modifier = Modifier.size(32.dp))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { actions.onJoinChannel(channel) },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (channel.isEnterRestricted) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.channel_password_locked),
                            tint = if (channel.canEnter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(16.dp),
                        )
                    }
                    Text(
                        if (channel.temporary) "# ${channel.name}" else "# ${channel.name}",
                        fontWeight = if (flags.isCurrentChannel) FontWeight.Bold else FontWeight.SemiBold,
                        fontStyle = if (flags.isLinked) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (flags.showLinkIcon) {
                        Icon(
                            painter = painterResource(R.drawable.ic_link_2),
                            contentDescription = stringResource(R.string.channel_linked),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp),
                        )
                    }
                    if (actions.showUserCount && channel.users.isNotEmpty()) {
                        Text(
                            " (${channel.users.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ChannelContextMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                showJoin = flags.showJoin,
                showListen = flags.showListen,
                listening = flags.listening,
                showShout = flags.showShout,
                shoutActive = flags.shoutActive,
                onShout = { shoutDialog = true },
                onStopShout = actions.onStopVoiceTarget,
                showAdd = flags.showAdd,
                showEdit = flags.showEdit,
                showRemove = flags.showRemove,
                showSend = flags.showSend,
                linkMenu = linkMenu,
                onJoin = { actions.onJoinChannel(channel) },
                onToggleListen = {
                    actions.onSetChannelListening(channel.id, !flags.listening)
                },
                onAdd = { addDialog = true },
                onEdit = { editDialog = true },
                onRemove = { removeDialog = true },
                onLink = { actions.onLinkChannel(channel.id) },
                onUnlink = { actions.onUnlinkChannel(channel.id) },
                onUnlinkAll = actions.onUnlinkAllChannels,
                onSend = { sendDialog = true },
            )
            if (shoutDialog) {
                ShoutToChannelDialog(
                    channelName = channel.name,
                    onConfirm = { links, children, group ->
                        shoutDialog = false
                        actions.onShoutToChannel(channel.id, links, children, group)
                    },
                    onDismiss = { shoutDialog = false },
                )
            }
            if (sendDialog) {
                SendTextMessageDialog(
                    title = stringResource(R.string.send_channel_message_title, channel.name),
                    onConfirm = { text ->
                        sendDialog = false
                        actions.onSendChat(channel.id, text)
                    },
                    onDismiss = { sendDialog = false },
                )
            }
            if (addDialog) {
                ChannelEditDialog(
                    channel = null,
                    parentName = channel.name,
                    forceTemporary = flags.forceTemporary,
                    incomingDescription = "",
                    incomingPassword = "",
                    onConfirm = { name, description, position, maxUsers, temporary, password ->
                        addDialog = false
                        actions.onCreateChannel(
                            channel.id, name, description, position, temporary, maxUsers, password,
                        )
                    },
                    onDismiss = { addDialog = false },
                )
            }
            if (editDialog) {
                ChannelEditDialog(
                    channel = channel,
                    parentName = channel.name,
                    // Editing an existing channel never sends the temporary flag.
                    forceTemporary = false,
                    incomingDescription = channel.description,
                    incomingPassword = actions.channelAclPassword
                        ?.takeIf { it.channelId == channel.id }
                        ?.password
                        ?: "",
                    onConfirm = { name, description, position, maxUsers, _, password ->
                        editDialog = false
                        actions.onUpdateChannel(
                            channel.id, name, description, position, maxUsers, password,
                        )
                    },
                    onDismiss = { editDialog = false },
                )
            }
            if (removeDialog) {
                RemoveChannelDialog(
                    channelName = channel.name,
                    onConfirm = {
                        removeDialog = false
                        actions.onRemoveChannel(channel.id)
                    },
                    onDismiss = { removeDialog = false },
                )
            }
        }
        if (!flags.collapsed) {
            channel.users.forEach { user ->
                key(user.session, user.isChannelListener, user.talkState) {
                    UserRow(
                        user = user,
                        indent = indent + 1,
                        actions = actions,
                    )
                }
            }
            channel.children.forEach { child ->
                ChannelNode(
                    channel = child,
                    indent = indent + 1,
                    collapsedIds = collapsedIds,
                    onToggleCollapsed = onToggleCollapsed,
                    actions = actions,
                )
            }
        }
    }
}
