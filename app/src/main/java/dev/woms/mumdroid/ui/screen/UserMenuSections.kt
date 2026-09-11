package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.User

/**
 * The groups [UserContextMenu] is assembled from: the move and moderation
 * submenus, the local-only block/ignore actions, and the whisper and private
 * message entries.
 *
 * Split out so the parent composable stays an orchestration of groups whose
 * visibility comes from [UserMenuVisibility], instead of one long list of menu
 * items; each group is a private composable that only receives the callbacks it
 * renders.
 *
 * The audio self-test entries and the nested-submenu primitive live here as
 * well: the self-test is offered on the local user's own row, and
 * [NestedDropdownMenu] exists only to hold these groups' submenus open, so
 * neither needs a file of its own.
 */

/** "Move" submenu: move the user here, or into a channel picked from a list. */
@Composable
internal fun MoveSubmenu(
    user: User,
    localChannelId: Int,
    visibility: UserMenuVisibility,
    moveSubOpen: Boolean,
    onMoveSubOpenChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onMoveUser: (Int, Int) -> Unit,
    onOpenMoveDialog: () -> Unit,
) {
    NestedDropdownMenu(
        label = stringResource(R.string.move_user_menu),
        icon = Icons.AutoMirrored.Filled.DriveFileMove,
        expanded = moveSubOpen,
        onExpandedChange = onMoveSubOpenChange,
    ) {
        if (visibility.showMoveHere) {
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
        if (visibility.showMoveTo) {
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

/** Moderation submenu: kick, ban, server mute, deafen, priority speaker. */
@Composable
internal fun AdminSubmenu(
    user: User,
    visibility: UserMenuVisibility,
    adminSubOpen: Boolean,
    onAdminSubOpenChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenKickDialog: () -> Unit,
    onOpenBanDialog: () -> Unit,
    onSetRemoteMute: (Int, Boolean) -> Unit,
    onSetRemoteDeafen: (Int, Boolean) -> Unit,
    onSetPrioritySpeaker: (Int, Boolean) -> Unit,
) {
    NestedDropdownMenu(
        label = stringResource(R.string.user_menu_admin),
        icon = Icons.Filled.AdminPanelSettings,
        expanded = adminSubOpen,
        onExpandedChange = onAdminSubOpenChange,
    ) {
        if (visibility.showKick) {
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
        if (visibility.showBan) {
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
        if (visibility.showMute) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (visibility.silencedByServer) R.string.unmute_user else R.string.mute_user
                        )
                    )
                },
                leadingIcon = { Icon(Icons.Filled.MicOff, contentDescription = null) },
                onClick = {
                    onAdminSubOpenChange(false)
                    onDismiss()
                    onSetRemoteMute(user.session, !visibility.silencedByServer)
                },
            )
        }
        if (visibility.showDeafen) {
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
        if (visibility.showPrioritySpeaker) {
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

/** Local-only actions: block the user's audio, ignore their messages. */
@Composable
internal fun BlockActions(
    user: User,
    onDismiss: () -> Unit,
    onSetLocalBlock: (Int, Boolean) -> Unit,
    onSetLocalIgnore: (Int, Boolean) -> Unit,
) {
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

/** Whisper to this one user, or stop an already-active whisper. */
@Composable
internal fun WhisperItem(
    user: User,
    whisperActive: Boolean,
    onDismiss: () -> Unit,
    onWhisper: () -> Unit,
) {
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

/** Open the private-message dialog for this user. */
@Composable
internal fun SendMessageItem(
    onDismiss: () -> Unit,
    onOpenSendDialog: () -> Unit,
) {
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
internal fun LoopbackMenuItems(
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

/**
 * One level of the context menus: a row that opens a second menu next to itself.
 *
 * Both the user and the channel long-press menus nest their long lists (move,
 * moderation, voice targets) behind an entry like this so the phone-sized menu
 * stays short. The submenu is a plain [DropdownMenu] anchored inside a [Box]:
 * Material has no first-class nested menu, and positioning it at a fixed
 * [DpOffset] keeps the submenu from covering its own parent entry.
 */
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
