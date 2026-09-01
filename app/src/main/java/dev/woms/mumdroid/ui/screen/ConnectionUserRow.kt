package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStatusIcon

@Composable
internal fun UserRow(
    user: User,
    indent: Int,
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
    onSendPrivateChat: (Int, String) -> Unit,
    onSetChannelListening: (Int, Boolean) -> Unit,
    onUserInformation: (Int, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var kickDialog by remember { mutableStateOf(false) }
    var banDialog by remember { mutableStateOf(false) }
    var registerDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var sendDialog by remember { mutableStateOf(false) }
    var moveSubOpen by remember { mutableStateOf(false) }
    var adminSubOpen by remember { mutableStateOf(false) }
    // Colors verified against the PC (Mumble desktop) client skin files
    // (themes/Default/muted_self.svg, muted_server.svg, muted_local.svg,
    // muted_suppressed.svg, priority_speaker.svg, status/text-missing.svg):
    //   - self-controlled mute/deafen: red  #EA4335
    //   - server/remote-imposed mute/deafen/priority speaker: blue  #44A3F2
    //   - channel ACL suppress (no Speak): green  #34A853
    //   - local mute or ignore messages: purple  #9B59B6
    val selfColor = Color(0xFFEA4335) // red (主动)
    val remoteColor = Color(0xFF44A3F2) // blue (服务器)
    val suppressColor = Color(0xFF34A853) // green (频道 ACL)
    val localColor = Color(0xFF9B59B6) // purple (本地)

    // Which flags are applied by someone else (server/remote) vs by the user themself
    // vs locally on this device only.
    val remoteMuted = user.mute || user.deaf || user.suppress
    val selfMuted = user.selfMute || user.selfDeaf
    val locallyBlocked = user.localBlock
    val silencedByServer = user.mute || user.suppress

    Box {
        Row(
            modifier = Modifier
                .padding(start = (indent * 16 + 8).dp, top = 2.dp, bottom = 2.dp)
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val showTalking = user.talking && !user.isSpeakBlocked
            if (user.isChannelListener) {
                Icon(
                    Icons.Filled.Hearing,
                    contentDescription = stringResource(R.string.channel_listener),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                )
            } else {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = stringResource(
                        if (showTalking) R.string.talking else R.string.not_talking
                    ),
                    tint = if (showTalking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                )
            }
            Text(
                text = user.name,
                fontWeight = if (user.isLocalUser) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (user.isChannelListener) FontStyle.Italic else FontStyle.Normal,
                style = MaterialTheme.typography.bodyMedium,
                color = if (remoteMuted || selfMuted || locallyBlocked) Color.Gray else Color.Unspecified,
                modifier = Modifier.weight(1f),
            )
            // Status icons after the name, left-to-right like desktop
            // UserModel::data (first declared = closest to the name):
            // priority speaker, server mute, suppress, self mute, local mute,
            // ignore messages, server deaf, self deaf.
            for (status in user.visibleStatusIcons()) {
                val image: ImageVector
                val desc: Int
                val tint: Color
                when (status) {
                    UserStatusIcon.PRIORITY_SPEAKER -> {
                        image = Icons.Filled.Campaign
                        desc = R.string.priority_speaker
                        tint = remoteColor
                    }
                    UserStatusIcon.SERVER_MUTE -> {
                        image = Icons.Filled.MicOff
                        desc = R.string.muted
                        tint = remoteColor
                    }
                    UserStatusIcon.SUPPRESS -> {
                        image = Icons.Filled.MicOff
                        desc = R.string.suppressed
                        tint = suppressColor
                    }
                    UserStatusIcon.SELF_MUTE -> {
                        image = Icons.Filled.MicOff
                        desc = R.string.muted
                        tint = selfColor
                    }
                    UserStatusIcon.LOCAL_MUTE -> {
                        image = Icons.Filled.MicOff
                        desc = R.string.blocked
                        tint = localColor
                    }
                    UserStatusIcon.LOCAL_IGNORE -> {
                        // Material Icons "Comments Disabled" is Chat Bubble Off.
                        image = Icons.Filled.CommentsDisabled
                        desc = R.string.messages_ignored
                        tint = localColor
                    }
                    UserStatusIcon.SERVER_DEAF -> {
                        image = Icons.AutoMirrored.Filled.VolumeOff
                        desc = R.string.deafened
                        tint = remoteColor
                    }
                    UserStatusIcon.SELF_DEAF -> {
                        image = Icons.AutoMirrored.Filled.VolumeOff
                        desc = R.string.deafened
                        tint = selfColor
                    }
                }
                Icon(
                    image,
                    contentDescription = stringResource(desc),
                    tint = tint,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // Desktop `qmUser_aboutToShow` order, with Move and moderation nested
        // so the long-press list stays short on a phone. Listener proxies use
        // a shorter menu (desktop `qmListener`).
        val isListener = user.isChannelListener
        val inOtherChannel = !user.isLocalUser && user.channelId != localChannelId
        val canMoveFrom = !isListener && !user.isLocalUser && canMoveInChannel(user.channelId)
        val moveDests = moveChannels.filter { it.id != user.channelId && canMoveInChannel(it.id) }
        val showMoveHere = canMoveFrom && inOtherChannel && canMoveInChannel(localChannelId)
        val showMoveTo = canMoveFrom && moveDests.isNotEmpty()
        val showMoveMenu = showMoveHere || showMoveTo
        val showMuteAction = !isListener && canMuteUser(user)
        val showDeafAction = !isListener && !user.isLocalUser && canAdministerChannel(user.channelId)
        val showPrioritySpeaker = !isListener && canPrioritySpeaker(user)
        val showKick = !isListener && !user.isLocalUser && canKickUser()
        val showBan = !isListener && !user.isLocalUser && canBanUser()
        val showAdminMenu = showKick || showBan || showMuteAction || showDeafAction || showPrioritySpeaker
        val showRegister = !isListener && canRegisterUser(user)
        val showStopListening = isListener && user.isLocalUser
        val showSendMessage = !user.isLocalUser && canTextMessage(
            if (isListener) user.listenerChannelId else user.channelId,
        )

        LaunchedEffect(menuOpen) {
            if (!menuOpen) {
                moveSubOpen = false
                adminSubOpen = false
            } else {
                onQueryChannelPermissions(user.channelId)
                if (isListener) onQueryChannelPermissions(user.listenerChannelId)
                onQueryChannelPermissions(localChannelId)
            }
        }
        LaunchedEffect(moveSubOpen, moveDialog) {
            if (moveSubOpen || moveDialog) {
                moveChannels.forEach { onQueryChannelPermissions(it.id) }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (showStopListening) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stop_listening_channel)) },
                    leadingIcon = { Icon(Icons.Filled.Hearing, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSetChannelListening(user.listenerChannelId, false)
                    },
                )
            }
            if (inOtherChannel) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.join_user_channel)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onJoinUserChannel(user.channelId)
                    },
                )
            }
            if (showMoveMenu) {
                NestedDropdownMenu(
                    label = stringResource(R.string.move_user_menu),
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    expanded = moveSubOpen,
                    onExpandedChange = { moveSubOpen = it },
                ) {
                    if (showMoveHere) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_user_here)) },
                            leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                            onClick = {
                                moveSubOpen = false
                                menuOpen = false
                                onMoveUser(user.session, localChannelId)
                            },
                        )
                    }
                    if (showMoveTo) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_user_to_channel)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                            onClick = {
                                moveSubOpen = false
                                menuOpen = false
                                moveDialog = true
                            },
                        )
                    }
                }
            }
            if (showAdminMenu) {
                if (inOtherChannel || showMoveMenu) {
                    HorizontalDivider()
                }
                NestedDropdownMenu(
                    label = stringResource(R.string.user_menu_admin),
                    icon = Icons.Filled.AdminPanelSettings,
                    expanded = adminSubOpen,
                    onExpandedChange = { adminSubOpen = it },
                ) {
                    if (showKick) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kick_user)) },
                            leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null) },
                            onClick = {
                                adminSubOpen = false
                                menuOpen = false
                                kickDialog = true
                            },
                        )
                    }
                    if (showBan) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ban_user)) },
                            leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                            onClick = {
                                adminSubOpen = false
                                menuOpen = false
                                banDialog = true
                            },
                        )
                    }
                    if (showMuteAction) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (silencedByServer) R.string.unmute_user else R.string.mute_user
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.MicOff, contentDescription = null) },
                            onClick = {
                                adminSubOpen = false
                                menuOpen = false
                                onSetRemoteMute(user.session, !silencedByServer)
                            },
                        )
                    }
                    if (showDeafAction) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (user.deaf) R.string.undeafen_user else R.string.deafen_user
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                            },
                            onClick = {
                                adminSubOpen = false
                                menuOpen = false
                                onSetRemoteDeafen(user.session, !user.deaf)
                            },
                        )
                    }
                    if (showPrioritySpeaker) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (user.prioritySpeaker) {
                                            R.string.revoke_priority_speaker
                                        } else {
                                            R.string.priority_speaker
                                        }
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                            onClick = {
                                adminSubOpen = false
                                menuOpen = false
                                onSetPrioritySpeaker(user.session, !user.prioritySpeaker)
                            },
                        )
                    }
                }
            }
            if (!user.isLocalUser && !isListener) {
                if (inOtherChannel || showMoveMenu || showAdminMenu || showStopListening) {
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(if (user.localBlock) R.string.unblock_user else R.string.block_user)) },
                    leadingIcon = { Icon(Icons.Filled.MicOff, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSetLocalBlock(user.session, !user.localBlock)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (user.localIgnore) {
                                    R.string.unignore_messages
                                } else {
                                    R.string.ignore_messages
                                }
                            )
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.CommentsDisabled, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSetLocalIgnore(user.session, !user.localIgnore)
                    },
                )
            }
            if (showSendMessage) {
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
            if (inOtherChannel || showMoveMenu || showAdminMenu || showStopListening
                || (!user.isLocalUser && !isListener) || showSendMessage
            ) {
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.user_information)) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onUserInformation(user.session, user.name)
                },
            )
            if (showRegister) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.register_user)) },
                    leadingIcon = { Icon(Icons.Filled.HowToReg, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        registerDialog = true
                    },
                )
            }
        }

        if (moveDialog) {
            MoveUserChannelDialog(
                userName = user.name,
                channels = moveDests,
                currentChannelId = user.channelId,
                onSelect = { channelId ->
                    moveDialog = false
                    onMoveUser(user.session, channelId)
                },
                onDismiss = { moveDialog = false },
            )
        }
        if (kickDialog) {
            KickUserDialog(
                userName = user.name,
                onConfirm = { reason ->
                    kickDialog = false
                    onKickUser(user.session, reason)
                },
                onDismiss = { kickDialog = false },
            )
        }
        if (banDialog) {
            BanUserDialog(
                userName = user.name,
                hasCertificate = user.hash.isNotEmpty(),
                showBanOptions = supportsSelectiveBan(),
                onConfirm = { reason, banCertificate, banIp, duration ->
                    banDialog = false
                    onBanUser(user.session, reason, banCertificate, banIp, duration)
                },
                onDismiss = { banDialog = false },
            )
        }
        if (registerDialog) {
            RegisterUserDialog(
                userName = user.name,
                isSelf = user.isLocalUser,
                onConfirm = {
                    registerDialog = false
                    onRegisterUser(user.session)
                },
                onDismiss = { registerDialog = false },
            )
        }
        if (sendDialog) {
            SendTextMessageDialog(
                title = stringResource(R.string.send_user_message_title, user.name),
                onConfirm = { text ->
                    sendDialog = false
                    onSendPrivateChat(user.session, text)
                },
                onDismiss = { sendDialog = false },
            )
        }
    }
}

@Composable
private fun NestedDropdownMenu(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(168.dp, 0.dp),
            content = content,
        )
    }
}

