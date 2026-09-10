package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.User

/**
 * Desktop `qmUser_aboutToShow` order, with Move and moderation nested
 * so the long-press list stays short on a phone. Listener proxies use
 * a shorter menu (desktop `qmListener`).
 *
 * Whisper / shout and the audio self-test both live here instead of in the
 * voice bar: on a phone the target is chosen by pointing at the user or the
 * channel it addresses, and a self-test is a per-user action, so neither needs
 * a permanent button in the bar.
 */
@Composable
internal fun UserContextMenu(
    user: User,
    expanded: Boolean,
    onDismiss: () -> Unit,
    localChannelId: Int,
    moveDests: List<ChannelPick>,
    moveSubOpen: Boolean,
    onMoveSubOpenChange: (Boolean) -> Unit,
    adminSubOpen: Boolean,
    onAdminSubOpenChange: (Boolean) -> Unit,
    onJoinUserChannel: (Int) -> Unit,
    onMoveUser: (Int, Int) -> Unit,
    onOpenMoveDialog: () -> Unit,
    onOpenKickDialog: () -> Unit,
    onOpenBanDialog: () -> Unit,
    onOpenRegisterDialog: () -> Unit,
    onOpenSendDialog: () -> Unit,
    onSetLocalBlock: (Int, Boolean) -> Unit,
    onSetLocalIgnore: (Int, Boolean) -> Unit,
    onSetRemoteMute: (Int, Boolean) -> Unit,
    onSetRemoteDeafen: (Int, Boolean) -> Unit,
    onSetPrioritySpeaker: (Int, Boolean) -> Unit,
    onSetChannelListening: (Int, Boolean) -> Unit,
    onUserInformation: (Int, String) -> Unit,
    canAdministerChannel: (Int) -> Boolean,
    canMuteUser: (User) -> Boolean,
    canPrioritySpeaker: (User) -> Boolean,
    canMoveInChannel: (Int) -> Boolean,
    canKickUser: () -> Boolean,
    canBanUser: () -> Boolean,
    canRegisterUser: (User) -> Boolean,
    canTextMessage: (Int) -> Boolean,
    showWhisper: Boolean,
    whisperActive: Boolean,
    onWhisper: () -> Unit,
    /** Audio self-test entries: only offered on the local user's own row. */
    showLoopback: Boolean,
    loopback: LoopbackMode,
    onSetLoopback: (LoopbackMode) -> Unit,
    /** Multi-select whisper / channel shout: reachable from your own row. */
    voiceTargetSubOpen: Boolean,
    onVoiceTargetSubOpenChange: (Boolean) -> Unit,
    onWhisperToUsers: () -> Unit,
    onShoutToChannel: () -> Unit,
) {
    val isListener = user.isChannelListener
    val inOtherChannel = !user.isLocalUser && user.channelId != localChannelId
    val canMoveFrom = !isListener && !user.isLocalUser && canMoveInChannel(user.channelId)
    val showMoveHere = canMoveFrom && inOtherChannel && canMoveInChannel(localChannelId)
    val showMoveTo = canMoveFrom && moveDests.isNotEmpty()
    val showMoveMenu = showMoveHere || showMoveTo
    val silencedByServer = user.mute || user.suppress
    val showMuteAction = !isListener && canMuteUser(user)
    val showDeafAction = !isListener && !user.isLocalUser && canAdministerChannel(user.channelId)
    val showPrioritySpeaker = !isListener && canPrioritySpeaker(user)
    val showKick = !isListener && !user.isLocalUser && canKickUser()
    val showBan = !isListener && !user.isLocalUser && canBanUser()
    val showAdminMenu = showKick || showBan || showMuteAction || showDeafAction || showPrioritySpeaker
    val showRegister = !isListener && canRegisterUser(user)
    val showStopListening = isListener && user.isLocalUser
    val showBlockActions = !user.isLocalUser && !isListener
    val showSendMessage = !user.isLocalUser && canTextMessage(
        if (isListener) user.listenerChannelId else user.channelId,
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (showStopListening) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stop_listening_channel)) },
                leadingIcon = { Icon(Icons.Filled.Hearing, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onSetChannelListening(user.listenerChannelId, false)
                },
            )
        }
        if (inOtherChannel) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.join_user_channel)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onJoinUserChannel(user.channelId)
                },
            )
        }
        if (showMoveMenu) {
            NestedDropdownMenu(
                label = stringResource(R.string.move_user_menu),
                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                expanded = moveSubOpen,
                onExpandedChange = onMoveSubOpenChange,
            ) {
                if (showMoveHere) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_user_here)) },
                        leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                        onClick = {
                            onMoveSubOpenChange(false)
                            onDismiss()
                            onMoveUser(user.session, localChannelId)
                        },
                    )
                }
                if (showMoveTo) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_user_to_channel)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                        onClick = {
                            onMoveSubOpenChange(false)
                            onDismiss()
                            onOpenMoveDialog()
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
                onExpandedChange = onAdminSubOpenChange,
            ) {
                if (showKick) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kick_user)) },
                        leadingIcon = { Icon(Icons.Filled.PersonRemove, contentDescription = null) },
                        onClick = {
                            onAdminSubOpenChange(false)
                            onDismiss()
                            onOpenKickDialog()
                        },
                    )
                }
                if (showBan) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ban_user)) },
                        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                        onClick = {
                            onAdminSubOpenChange(false)
                            onDismiss()
                            onOpenBanDialog()
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
                            onAdminSubOpenChange(false)
                            onDismiss()
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
                            onAdminSubOpenChange(false)
                            onDismiss()
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
                            onAdminSubOpenChange(false)
                            onDismiss()
                            onSetPrioritySpeaker(user.session, !user.prioritySpeaker)
                        },
                    )
                }
            }
        }
        if (showBlockActions) {
            if (inOtherChannel || showMoveMenu || showAdminMenu || showStopListening) {
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (user.localBlock) R.string.unblock_user else R.string.block_user)) },
                leadingIcon = { Icon(Icons.Filled.MicOff, contentDescription = null) },
                onClick = {
                    onDismiss()
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
                    onDismiss()
                    onSetLocalIgnore(user.session, !user.localIgnore)
                },
            )
        }
        if (showWhisper) {
            if (inOtherChannel || showMoveMenu || showAdminMenu || showStopListening) {
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (whisperActive) R.string.stop_voice_target
                            else R.string.whisper_to_user,
                            user.name,
                        )
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    onWhisper()
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
                    onDismiss()
                    onOpenSendDialog()
                },
            )
        }
        if (showLoopback) {
            if (showBlockActions || showWhisper || showSendMessage) {
                HorizontalDivider()
            }
            // Whisper and shout are normally started from the row of the user /
            // channel they address; the two pickers below are the entry points
            // for everything a row cannot express (whispering to several users
            // at once, shouting to a channel that is not on screen), which is
            // why they hang off your own row.
            NestedDropdownMenu(
                label = stringResource(R.string.voice_target_label),
                icon = Icons.Filled.RecordVoiceOver,
                expanded = voiceTargetSubOpen,
                onExpandedChange = onVoiceTargetSubOpenChange,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.whisper_to_ellipsis)) },
                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null) },
                    onClick = {
                        onVoiceTargetSubOpenChange(false)
                        onDismiss()
                        onWhisperToUsers()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shout_to_channel_ellipsis)) },
                    leadingIcon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                    onClick = {
                        onVoiceTargetSubOpenChange(false)
                        onDismiss()
                        onShoutToChannel()
                    },
                )
            }
            LoopbackMenuItems(
                loopback = loopback,
                enabled = !user.isChannelListener,
                onSetLoopback = onSetLoopback,
            )
        }
        if (showBlockActions || showWhisper || showSendMessage || showLoopback) {
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.user_information)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = {
                onDismiss()
                onUserInformation(user.session, user.name)
            },
        )
        if (showRegister) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.register_user)) },
                leadingIcon = { Icon(Icons.Filled.HowToReg, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpenRegisterDialog()
                },
            )
        }
    }
}

@Composable
internal fun NestedDropdownMenu(
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

/**
 * Audio self-test ("loopback") entries. Both are switches rather than a single
 * item, because the two halves of the feature are independently worth knowing
 * about: the self-test itself (the microphone becomes audible to nobody else)
 * and where the audio is looped back, which starts local — offline, no server
 * round trip — and can be moved to the server to exercise the whole uplink.
 *
 * The self-test is not persisted, so turning it on always means "local, right
 * now", and the row is also the only place it can be switched off again.
 */
@Composable
private fun LoopbackMenuItems(
    loopback: LoopbackMode,
    enabled: Boolean,
    onSetLoopback: (LoopbackMode) -> Unit,
) {
    val active = loopback.isActive
    DropdownMenuItem(
        text = { Text(stringResource(R.string.loopback_self_test)) },
        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        trailingIcon = {
            Switch(
                checked = active,
                enabled = enabled,
                onCheckedChange = { on -> onSetLoopback(LoopbackMode.of(enabled = on, server = false)) },
            )
        },
        onClick = { onSetLoopback(LoopbackMode.of(enabled = !active, server = false)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.loopback_use_server)) },
        leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
        trailingIcon = {
            Switch(
                checked = loopback.isServer,
                enabled = enabled && active,
                onCheckedChange = { server ->
                    onSetLoopback(LoopbackMode.of(enabled = true, server = server))
                },
            )
        },
        // The switch already expresses both states; the row only matters while
        // the self-test runs, so a tap on it is a no-op when nothing loops.
        enabled = enabled && active,
        onClick = {
            onSetLoopback(LoopbackMode.of(enabled = true, server = !loopback.isServer))
        },
    )
}
