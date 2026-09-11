package dev.woms.mumdroid.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
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
