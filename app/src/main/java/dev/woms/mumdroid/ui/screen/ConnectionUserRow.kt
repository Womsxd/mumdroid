package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStatusIcon

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
    )
}

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

        val moveDests = moveChannels.filter { it.id != user.channelId && canMoveInChannel(it.id) }

        LaunchedEffect(menuOpen) {
            if (!menuOpen) {
                moveSubOpen = false
                adminSubOpen = false
            } else {
                onQueryChannelPermissions(user.channelId)
                if (user.isChannelListener) onQueryChannelPermissions(user.listenerChannelId)
                onQueryChannelPermissions(localChannelId)
            }
        }
        LaunchedEffect(moveSubOpen, moveDialog) {
            if (moveSubOpen || moveDialog) {
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
            onOpenMoveDialog = { moveDialog = true },
            onOpenKickDialog = { kickDialog = true },
            onOpenBanDialog = { banDialog = true },
            onOpenRegisterDialog = { registerDialog = true },
            onOpenSendDialog = { sendDialog = true },
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
        )

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

