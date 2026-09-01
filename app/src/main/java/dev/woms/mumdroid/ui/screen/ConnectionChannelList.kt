package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelLinks
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.User

/** Recursive channel list with users. */
@Composable
internal fun ChannelList(
    channels: List<Channel>,
    users: List<User>,
    onJoinChannel: (Channel) -> Unit,
    onJoinUserChannel: (Int) -> Unit,
    onMoveUser: (Int, Int) -> Unit,
    localChannelId: Int,
    moveChannels: List<ChannelPick>,
    onSetLocalBlock: (Int, Boolean) -> Unit,
    onSetLocalIgnore: (Int, Boolean) -> Unit,
    onSetRemoteMute: (Int, Boolean) -> Unit,
    onSetRemoteDeafen: (Int, Boolean) -> Unit,
    onSetPrioritySpeaker: (Int, Boolean) -> Unit,
    onKickUser: (Int, String) -> Unit,
    onBanUser: (Int, String, Boolean, Boolean, Int) -> Unit,
    onRegisterUser: (Int) -> Unit,
    canAdministerChannel: (Int) -> Boolean,
    canMuteUser: (User) -> Boolean,
    canPrioritySpeaker: (User) -> Boolean,
    canMoveInChannel: (Int) -> Boolean,
    onQueryChannelPermissions: (Int) -> Unit,
    canKickUser: () -> Boolean,
    canBanUser: () -> Boolean,
    canRegisterUser: (User) -> Boolean,
    supportsSelectiveBan: () -> Boolean,
    canTextMessage: (Int) -> Boolean,
    canListen: (Int) -> Boolean,
    supportsChannelListen: () -> Boolean,
    listeningChannels: Set<Int>,
    onSendChat: (Int, String) -> Unit,
    onSendPrivateChat: (Int, String) -> Unit,
    onSetChannelListening: (Int, Boolean) -> Unit,
    canWriteChannel: (Int) -> Boolean,
    canAddChannel: (Int) -> Boolean,
    canMakePermanentChannel: (Int) -> Boolean,
    canLinkChannel: (Int) -> Boolean,
    onLinkChannel: (Int) -> Unit,
    onUnlinkChannel: (Int) -> Unit,
    onUnlinkAllChannels: () -> Unit,
    onCreateChannel: (Int, String, String, Int, Boolean, Int, String) -> Unit,
    onUpdateChannel: (Int, String, String, Int, Int, String) -> Unit,
    onRemoveChannel: (Int) -> Unit,
    onRequestChannelDescription: (Int) -> Unit,
    onRequestChannelAcl: (Int) -> Unit,
    channelAclPassword: ChannelAclPassword?,
    permissionEpoch: Int,
    showUserCount: Boolean,
    onUserInformation: (Int, String) -> Unit,
) {
    var collapsedIds by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val collapsed = collapsedIds.toSet()
    val linksById = remember(channels) { ChannelLinks.collect(channels) }
    val homeAllLinks = remember(linksById, localChannelId) {
        ChannelLinks.allLinkedIds(linksById, localChannelId)
    }
    val homeDirectLinks = linksById[localChannelId] ?: emptySet()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelNode(
                channel = channel, indent = 0, onJoinChannel = onJoinChannel,
                onJoinUserChannel = onJoinUserChannel, onMoveUser = onMoveUser,
                localChannelId = localChannelId, moveChannels = moveChannels,
                collapsedIds = collapsed,
                onToggleCollapsed = {
                    collapsedIds = ChannelTree.toggleCollapsed(collapsed, it).toList()
                },
                onSetLocalBlock = onSetLocalBlock,
                onSetLocalIgnore = onSetLocalIgnore,
                onSetRemoteMute = onSetRemoteMute, onSetRemoteDeafen = onSetRemoteDeafen,
                onSetPrioritySpeaker = onSetPrioritySpeaker,
                onKickUser = onKickUser, onBanUser = onBanUser,
                onRegisterUser = onRegisterUser,
                canAdministerChannel = canAdministerChannel,
                canMuteUser = canMuteUser,
                canPrioritySpeaker = canPrioritySpeaker,
                canMoveInChannel = canMoveInChannel,
                onQueryChannelPermissions = onQueryChannelPermissions,
                canKickUser = canKickUser, canBanUser = canBanUser,
                canRegisterUser = canRegisterUser,
                supportsSelectiveBan = supportsSelectiveBan,
                canTextMessage = canTextMessage,
                canListen = canListen,
                supportsChannelListen = supportsChannelListen,
                listeningChannels = listeningChannels,
                onSendChat = onSendChat,
                onSendPrivateChat = onSendPrivateChat,
                onSetChannelListening = onSetChannelListening,
                canWriteChannel = canWriteChannel,
                canAddChannel = canAddChannel,
                canMakePermanentChannel = canMakePermanentChannel,
                canLinkChannel = canLinkChannel,
                onLinkChannel = onLinkChannel,
                onUnlinkChannel = onUnlinkChannel,
                onUnlinkAllChannels = onUnlinkAllChannels,
                homeAllLinks = homeAllLinks,
                homeDirectLinks = homeDirectLinks,
                onCreateChannel = onCreateChannel,
                onUpdateChannel = onUpdateChannel,
                onRemoveChannel = onRemoveChannel,
                onRequestChannelDescription = onRequestChannelDescription,
                onRequestChannelAcl = onRequestChannelAcl,
                channelAclPassword = channelAclPassword,
                permissionEpoch = permissionEpoch,
                showUserCount = showUserCount,
                onUserInformation = onUserInformation,
            )
        }
    }
}

@Composable
private fun ChannelNode(
    channel: Channel, indent: Int, onJoinChannel: (Channel) -> Unit,
    onJoinUserChannel: (Int) -> Unit, onMoveUser: (Int, Int) -> Unit,
    localChannelId: Int, moveChannels: List<ChannelPick>,
    collapsedIds: Set<Int>,
    onToggleCollapsed: (Int) -> Unit,
    onSetLocalBlock: (Int, Boolean) -> Unit,
    onSetLocalIgnore: (Int, Boolean) -> Unit,
    onSetRemoteMute: (Int, Boolean) -> Unit, onSetRemoteDeafen: (Int, Boolean) -> Unit,
    onSetPrioritySpeaker: (Int, Boolean) -> Unit,
    onKickUser: (Int, String) -> Unit, onBanUser: (Int, String, Boolean, Boolean, Int) -> Unit,
    onRegisterUser: (Int) -> Unit,
    canAdministerChannel: (Int) -> Boolean,
    canMuteUser: (User) -> Boolean,
    canPrioritySpeaker: (User) -> Boolean,
    canMoveInChannel: (Int) -> Boolean,
    onQueryChannelPermissions: (Int) -> Unit,
    canKickUser: () -> Boolean, canBanUser: () -> Boolean,
    canRegisterUser: (User) -> Boolean,
    supportsSelectiveBan: () -> Boolean,
    canTextMessage: (Int) -> Boolean,
    canListen: (Int) -> Boolean,
    supportsChannelListen: () -> Boolean,
    listeningChannels: Set<Int>,
    onSendChat: (Int, String) -> Unit,
    onSendPrivateChat: (Int, String) -> Unit,
    onSetChannelListening: (Int, Boolean) -> Unit,
    canWriteChannel: (Int) -> Boolean,
    canAddChannel: (Int) -> Boolean,
    canMakePermanentChannel: (Int) -> Boolean,
    canLinkChannel: (Int) -> Boolean,
    onLinkChannel: (Int) -> Unit,
    onUnlinkChannel: (Int) -> Unit,
    onUnlinkAllChannels: () -> Unit,
    homeAllLinks: Set<Int>,
    homeDirectLinks: Set<Int>,
    onCreateChannel: (Int, String, String, Int, Boolean, Int, String) -> Unit,
    onUpdateChannel: (Int, String, String, Int, Int, String) -> Unit,
    onRemoveChannel: (Int) -> Unit,
    onRequestChannelDescription: (Int) -> Unit,
    onRequestChannelAcl: (Int) -> Unit,
    channelAclPassword: ChannelAclPassword?,
    permissionEpoch: Int,
    showUserCount: Boolean,
    onUserInformation: (Int, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sendDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val listening = channel.id in listeningChannels
    val showJoin = channel.id != localChannelId
    val showSend = canTextMessage(channel.id)
    val showListen = supportsChannelListen() && (canListen(channel.id) || listening)
    val showAdd = !channel.temporary && canAddChannel(channel.id)
    val showEdit = canWriteChannel(channel.id)
    val showRemove = showEdit && channel.id != 0
    val showAdmin = showAdd || showEdit || showRemove
    val forceTemporary = showAdd && !canMakePermanentChannel(channel.id)
    val canCollapse = ChannelTree.canCollapse(channel)
    val collapsed = canCollapse && channel.id in collapsedIds
    val linkMenu = ChannelLinks.menu(
        homeId = localChannelId,
        targetId = channel.id,
        homeDirectLinks = homeDirectLinks,
        targetInHomeComponent = channel.id in homeAllLinks,
        homeCanLink = canLinkChannel(localChannelId),
        targetCanLink = canLinkChannel(channel.id),
    )
    val isCurrentChannel = channel.id == localChannelId
    val isLinked = channel.id in homeAllLinks && homeAllLinks.size > 1
    val showLinkIcon = isLinked && !isCurrentChannel

    LaunchedEffect(menuOpen, permissionEpoch) {
        if (menuOpen) {
            onQueryChannelPermissions(channel.id)
            onQueryChannelPermissions(localChannelId)
        }
    }
    LaunchedEffect(editDialog) {
        if (editDialog) {
            onRequestChannelDescription(channel.id)
            onRequestChannelAcl(channel.id)
        }
    }

    Column(modifier = Modifier.padding(start = (indent * 16).dp)) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCollapse) {
                    IconButton(
                        onClick = { onToggleCollapsed(channel.id) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (collapsed) {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = stringResource(
                                if (collapsed) R.string.expand_channel
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
                            onClick = { onJoinChannel(channel) },
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
                        fontWeight = if (isCurrentChannel) FontWeight.Bold else FontWeight.SemiBold,
                        fontStyle = if (isLinked) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (showLinkIcon) {
                        Icon(
                            painter = painterResource(R.drawable.ic_link_2),
                            contentDescription = stringResource(R.string.channel_linked),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp),
                        )
                    }
                    if (showUserCount && channel.users.isNotEmpty()) {
                        Text(
                            " (${channel.users.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                if (showJoin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.join_channel)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onJoinChannel(channel)
                        },
                    )
                }
                if (showListen) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (listening) R.string.stop_listening_channel
                                    else R.string.listening_channel,
                                )
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Hearing, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSetChannelListening(channel.id, !listening)
                        },
                    )
                }
                if ((showJoin || showListen) && (showAdmin || linkMenu.any || showSend)) {
                    HorizontalDivider()
                }
                if (showAdd) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_channel)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            addDialog = true
                        },
                    )
                }
                if (showEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_channel)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            editDialog = true
                        },
                    )
                }
                if (showRemove) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_channel)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            removeDialog = true
                        },
                    )
                }
                if (showAdmin && (linkMenu.any || showSend)) {
                    HorizontalDivider()
                }
                if (linkMenu.showLink) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.link_channel)) },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onLinkChannel(channel.id)
                        },
                    )
                }
                if (linkMenu.showUnlink) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unlink_channel)) },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onUnlinkChannel(channel.id)
                        },
                    )
                }
                if (linkMenu.showUnlinkAll) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unlink_all_channels)) },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onUnlinkAllChannels()
                        },
                    )
                }
                if (linkMenu.any && showSend) {
                    HorizontalDivider()
                }
                if (showSend) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.send_message)) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            sendDialog = true
                        },
                    )
                }
            }
            if (sendDialog) {
                SendTextMessageDialog(
                    title = stringResource(R.string.send_channel_message_title, channel.name),
                    onConfirm = { text ->
                        sendDialog = false
                        onSendChat(channel.id, text)
                    },
                    onDismiss = { sendDialog = false },
                )
            }
            if (addDialog) {
                ChannelEditDialog(
                    channel = null,
                    parentName = channel.name,
                    forceTemporary = forceTemporary,
                    incomingDescription = "",
                    incomingPassword = "",
                    onConfirm = { name, description, position, maxUsers, temporary, password ->
                        addDialog = false
                        onCreateChannel(
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
                    forceTemporary = false,
                    incomingDescription = channel.description,
                    incomingPassword = channelAclPassword
                        ?.takeIf { it.channelId == channel.id }
                        ?.password
                        ?: "",
                    onConfirm = { name, description, position, maxUsers, _, password ->
                        editDialog = false
                        onUpdateChannel(
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
                        onRemoveChannel(channel.id)
                    },
                    onDismiss = { removeDialog = false },
                )
            }
        }
        if (!collapsed) {
            channel.users.forEach { user ->
                key(user.session, user.isChannelListener, user.talking) {
                    UserRow(
                        user = user, indent = indent + 1,
                        onJoinUserChannel = onJoinUserChannel, onMoveUser = onMoveUser,
                        localChannelId = localChannelId, moveChannels = moveChannels,
                        onSetLocalBlock = onSetLocalBlock,
                        onSetLocalIgnore = onSetLocalIgnore,
                        onSetRemoteMute = onSetRemoteMute, onSetRemoteDeafen = onSetRemoteDeafen,
                        onSetPrioritySpeaker = onSetPrioritySpeaker,
                        onKickUser = onKickUser, onBanUser = onBanUser,
                        onRegisterUser = onRegisterUser,
                        canAdministerChannel = canAdministerChannel,
                        canMuteUser = canMuteUser,
                        canPrioritySpeaker = canPrioritySpeaker,
                        canMoveInChannel = canMoveInChannel,
                        onQueryChannelPermissions = onQueryChannelPermissions,
                        canKickUser = canKickUser, canBanUser = canBanUser,
                        canRegisterUser = canRegisterUser,
                        supportsSelectiveBan = supportsSelectiveBan,
                        canTextMessage = canTextMessage,
                        onSendPrivateChat = onSendPrivateChat,
                        onSetChannelListening = onSetChannelListening,
                        onUserInformation = onUserInformation,
                    )
                }
            }
            channel.children.forEach { child ->
                ChannelNode(
                    channel = child, indent = indent + 1, onJoinChannel = onJoinChannel,
                    onJoinUserChannel = onJoinUserChannel, onMoveUser = onMoveUser,
                    localChannelId = localChannelId, moveChannels = moveChannels,
                    collapsedIds = collapsedIds,
                    onToggleCollapsed = onToggleCollapsed,
                    onSetLocalBlock = onSetLocalBlock,
                    onSetLocalIgnore = onSetLocalIgnore,
                    onSetRemoteMute = onSetRemoteMute, onSetRemoteDeafen = onSetRemoteDeafen,
                    onSetPrioritySpeaker = onSetPrioritySpeaker,
                    onKickUser = onKickUser, onBanUser = onBanUser,
                    onRegisterUser = onRegisterUser,
                    canAdministerChannel = canAdministerChannel,
                    canMuteUser = canMuteUser,
                    canPrioritySpeaker = canPrioritySpeaker,
                    canMoveInChannel = canMoveInChannel,
                    onQueryChannelPermissions = onQueryChannelPermissions,
                    canKickUser = canKickUser, canBanUser = canBanUser,
                    canRegisterUser = canRegisterUser,
                    supportsSelectiveBan = supportsSelectiveBan,
                    canTextMessage = canTextMessage,
                    canListen = canListen,
                    supportsChannelListen = supportsChannelListen,
                    listeningChannels = listeningChannels,
                    onSendChat = onSendChat,
                    onSendPrivateChat = onSendPrivateChat,
                    onSetChannelListening = onSetChannelListening,
                    canWriteChannel = canWriteChannel,
                    canAddChannel = canAddChannel,
                    canMakePermanentChannel = canMakePermanentChannel,
                    canLinkChannel = canLinkChannel,
                    onLinkChannel = onLinkChannel,
                    onUnlinkChannel = onUnlinkChannel,
                    onUnlinkAllChannels = onUnlinkAllChannels,
                    homeAllLinks = homeAllLinks,
                    homeDirectLinks = homeDirectLinks,
                    onCreateChannel = onCreateChannel,
                    onUpdateChannel = onUpdateChannel,
                    onRemoveChannel = onRemoveChannel,
                    onRequestChannelDescription = onRequestChannelDescription,
                    onRequestChannelAcl = onRequestChannelAcl,
                    channelAclPassword = channelAclPassword,
                    permissionEpoch = permissionEpoch,
                    showUserCount = showUserCount,
                    onUserInformation = onUserInformation,
                )
            }
        }
    }
}
