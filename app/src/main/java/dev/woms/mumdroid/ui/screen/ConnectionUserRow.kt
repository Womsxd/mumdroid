package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.User

/**
 * Renders a user row from the shared [ChannelTreeActions] bundle, so the
 * recursive channel tree does not have to splat 40 callbacks per user.
 */
@Composable
internal fun UserRow(
    user: User,
    indent: Int,
    actions: ChannelTreeActions,
) {
    UserRow(
        user = user,
        indent = indent,
        onJoinUserChannel = actions.onJoinUserChannel,
        onMoveUser = actions.onMoveUser,
        localChannelId = actions.localChannelId,
        moveChannels = actions.moveChannels,
        onSetLocalBlock = actions.onSetLocalBlock,
        onSetLocalIgnore = actions.onSetLocalIgnore,
        onSetRemoteMute = actions.onSetRemoteMute,
        onSetRemoteDeafen = actions.onSetRemoteDeafen,
        onSetPrioritySpeaker = actions.onSetPrioritySpeaker,
        onKickUser = actions.onKickUser,
        onBanUser = actions.onBanUser,
        onRegisterUser = actions.onRegisterUser,
        canAdministerChannel = actions.canAdministerChannel,
        canMuteUser = actions.canMuteUser,
        canPrioritySpeaker = actions.canPrioritySpeaker,
        canMoveInChannel = actions.canMoveInChannel,
        onQueryChannelPermissions = actions.onQueryChannelPermissions,
        canKickUser = actions.canKickUser,
        canBanUser = actions.canBanUser,
        canRegisterUser = actions.canRegisterUser,
        supportsSelectiveBan = actions.supportsSelectiveBan,
        canTextMessage = actions.canTextMessage,
        onSendPrivateChat = actions.onSendPrivateChat,
        onSetChannelListening = actions.onSetChannelListening,
        onUserInformation = actions.onUserInformation,
        onWhisperToUser = actions.onWhisperToUser,
        onStopVoiceTarget = actions.onStopVoiceTarget,
        whisperSessions = actions.whisperSessions,
        mayWhisper = actions.mayWhisper,
        loopback = actions.loopback,
        onSetLoopback = actions.onSetLoopback,
        onWhisperToUsers = actions.onWhisperToUsers,
        onShoutToChannel = actions.onShoutToChannelPicker,
    )
}

/** A single user row inside a channel, with its context menu and dialogs. */
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
    onWhisperToUser: (Int) -> Unit,
    onStopVoiceTarget: () -> Unit,
    whisperSessions: Set<Int>,
    mayWhisper: (Int) -> Boolean,
    loopback: LoopbackMode,
    onSetLoopback: (LoopbackMode) -> Unit,
    onWhisperToUsers: () -> Unit,
    onShoutToChannel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dialogs = remember { UserRowDialogs() }
    var moveSubOpen by remember { mutableStateOf(false) }
    var adminSubOpen by remember { mutableStateOf(false) }
    var voiceTargetSubOpen by remember { mutableStateOf(false) }
    // Which flags are applied by someone else (server/remote) vs by the user themself
    // vs locally on this device only.
    val whisperActive = user.session in whisperSessions
    val showWhisper = !user.isLocalUser && !user.isChannelListener &&
        (whisperActive || mayWhisper(user.channelId))

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
            UserRowLabel(user)
        }

        val moveDests = moveChannels.filter { it.id != user.channelId && canMoveInChannel(it.id) }

        LaunchedEffect(menuOpen) {
            if (!menuOpen) {
                moveSubOpen = false
                adminSubOpen = false
                voiceTargetSubOpen = false
            } else {
                onQueryChannelPermissions(user.channelId)
                if (user.isChannelListener) onQueryChannelPermissions(user.listenerChannelId)
                onQueryChannelPermissions(localChannelId)
            }
        }
        LaunchedEffect(moveSubOpen, dialogs.move) {
            if (moveSubOpen || dialogs.move) {
                moveChannels.forEach { onQueryChannelPermissions(it.id) }
            }
        }

        UserContextMenu(
            user = user,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            localChannelId = localChannelId,
            moveDests = moveDests,
            moveSubOpen = moveSubOpen,
            onMoveSubOpenChange = { moveSubOpen = it },
            adminSubOpen = adminSubOpen,
            onAdminSubOpenChange = { adminSubOpen = it },
            onJoinUserChannel = onJoinUserChannel,
            onMoveUser = onMoveUser,
            onOpenMoveDialog = { dialogs.move = true },
            onOpenKickDialog = { dialogs.kick = true },
            onOpenBanDialog = { dialogs.ban = true },
            onOpenRegisterDialog = { dialogs.register = true },
            onOpenSendDialog = { dialogs.send = true },
            onSetLocalBlock = onSetLocalBlock,
            onSetLocalIgnore = onSetLocalIgnore,
            onSetRemoteMute = onSetRemoteMute,
            onSetRemoteDeafen = onSetRemoteDeafen,
            onSetPrioritySpeaker = onSetPrioritySpeaker,
            onSetChannelListening = onSetChannelListening,
            onUserInformation = onUserInformation,
            canAdministerChannel = canAdministerChannel,
            canMuteUser = canMuteUser,
            canPrioritySpeaker = canPrioritySpeaker,
            canMoveInChannel = canMoveInChannel,
            canKickUser = canKickUser,
            canBanUser = canBanUser,
            canRegisterUser = canRegisterUser,
            canTextMessage = canTextMessage,
            showWhisper = showWhisper,
            whisperActive = whisperActive,
            // The self-test only makes sense on your own row: it loops your own
            // microphone back to you, it is not a per-user action on somebody
            // else.
            showLoopback = user.isLocalUser,
            loopback = loopback,
            onSetLoopback = onSetLoopback,
            voiceTargetSubOpen = voiceTargetSubOpen,
            onVoiceTargetSubOpenChange = { voiceTargetSubOpen = it },
            onWhisperToUsers = onWhisperToUsers,
            onShoutToChannel = onShoutToChannel,
            onWhisper = {
                // Clearing an already-active whisper restores regular speech;
                // picking the same user again would be a no-op re-registration.
                if (whisperActive) onStopVoiceTarget() else onWhisperToUser(user.session)
            },
        )

        UserRowDialogHost(
            user = user,
            dialogs = dialogs,
            moveDests = moveDests,
            supportsSelectiveBan = supportsSelectiveBan,
            onMoveUser = onMoveUser,
            onKickUser = onKickUser,
            onBanUser = onBanUser,
            onRegisterUser = onRegisterUser,
            onSendPrivateChat = onSendPrivateChat,
        )
    }
}

