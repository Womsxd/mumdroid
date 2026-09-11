package dev.woms.mumdroid.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
 *
 * Which entries show — and where the group dividers go — is decided by
 * [UserMenuVisibility]; this composable only renders them.
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
    val visibility = UserMenuVisibility.of(
        user = user,
        localChannelId = localChannelId,
        moveDests = moveDests,
        showWhisper = showWhisper,
        showLoopback = showLoopback,
        canAdministerChannel = canAdministerChannel,
        canMuteUser = canMuteUser,
        canPrioritySpeaker = canPrioritySpeaker,
        canMoveInChannel = canMoveInChannel,
        canKickUser = canKickUser,
        canBanUser = canBanUser,
        canRegisterUser = canRegisterUser,
        canTextMessage = canTextMessage,
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (visibility.showStopListening) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stop_listening_channel)) },
                leadingIcon = { Icon(Icons.Filled.Hearing, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onSetChannelListening(user.listenerChannelId, false)
                },
            )
        }
        if (visibility.inOtherChannel) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.join_user_channel)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onJoinUserChannel(user.channelId)
                },
            )
        }
        if (visibility.showMoveMenu) {
            MoveSubmenu(
                user = user,
                localChannelId = localChannelId,
                visibility = visibility,
                moveSubOpen = moveSubOpen,
                onMoveSubOpenChange = onMoveSubOpenChange,
                onDismiss = onDismiss,
                onMoveUser = onMoveUser,
                onOpenMoveDialog = onOpenMoveDialog,
            )
        }
        if (visibility.showAdminMenu) {
            if (visibility.dividerBeforeAdmin) {
                HorizontalDivider()
            }
            AdminSubmenu(
                user = user,
                visibility = visibility,
                adminSubOpen = adminSubOpen,
                onAdminSubOpenChange = onAdminSubOpenChange,
                onDismiss = onDismiss,
                onOpenKickDialog = onOpenKickDialog,
                onOpenBanDialog = onOpenBanDialog,
                onSetRemoteMute = onSetRemoteMute,
                onSetRemoteDeafen = onSetRemoteDeafen,
                onSetPrioritySpeaker = onSetPrioritySpeaker,
            )
        }
        if (visibility.showBlockActions) {
            if (visibility.dividerBeforeLocalActions) {
                HorizontalDivider()
            }
            BlockActions(
                user = user,
                onDismiss = onDismiss,
                onSetLocalBlock = onSetLocalBlock,
                onSetLocalIgnore = onSetLocalIgnore,
            )
        }
        if (visibility.showWhisper) {
            if (visibility.dividerBeforeLocalActions) {
                HorizontalDivider()
            }
            WhisperItem(
                user = user,
                whisperActive = whisperActive,
                onDismiss = onDismiss,
                onWhisper = onWhisper,
            )
        }
        if (visibility.showSendMessage) {
            SendMessageItem(onDismiss = onDismiss, onOpenSendDialog = onOpenSendDialog)
        }
        if (visibility.showLoopback) {
            if (visibility.dividerBeforeVoiceTarget) {
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
        if (visibility.dividerBeforeFooter) {
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
        if (visibility.showRegister) {
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
