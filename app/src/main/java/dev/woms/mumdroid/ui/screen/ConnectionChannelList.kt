package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelLinks
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.LoopbackMode
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
    onShoutToChannel: (Int, Boolean, Boolean, String) -> Unit,
    onWhisperToUser: (Int) -> Unit,
    onStopVoiceTarget: () -> Unit,
    activeShoutChannelId: Int?,
    whisperSessions: Set<Int>,
    mayWhisper: (Int) -> Boolean,
    loopback: LoopbackMode,
    onSetLoopback: (LoopbackMode) -> Unit,
    onWhisperToUsers: () -> Unit,
    onShoutToChannelPicker: () -> Unit,
) {
    var collapsedIds by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val collapsed = collapsedIds.toSet()
    val linksById = remember(channels) { ChannelLinks.collect(channels) }
    val homeAllLinks = remember(linksById, localChannelId) {
        ChannelLinks.allLinkedIds(linksById, localChannelId)
    }
    val homeDirectLinks = linksById[localChannelId] ?: emptySet()
    val actions = ChannelTreeActions(
        onJoinChannel = onJoinChannel,
        onJoinUserChannel = onJoinUserChannel,
        onMoveUser = onMoveUser,
        localChannelId = localChannelId,
        moveChannels = moveChannels,
        onSetLocalBlock = onSetLocalBlock,
        onSetLocalIgnore = onSetLocalIgnore,
        onSetRemoteMute = onSetRemoteMute,
        onSetRemoteDeafen = onSetRemoteDeafen,
        onSetPrioritySpeaker = onSetPrioritySpeaker,
        onKickUser = onKickUser,
        onBanUser = onBanUser,
        onRegisterUser = onRegisterUser,
        canAdministerChannel = canAdministerChannel,
        canMuteUser = canMuteUser,
        canPrioritySpeaker = canPrioritySpeaker,
        canMoveInChannel = canMoveInChannel,
        onQueryChannelPermissions = onQueryChannelPermissions,
        canKickUser = canKickUser,
        canBanUser = canBanUser,
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
        onCreateChannel = onCreateChannel,
        onUpdateChannel = onUpdateChannel,
        onRemoveChannel = onRemoveChannel,
        onRequestChannelDescription = onRequestChannelDescription,
        onRequestChannelAcl = onRequestChannelAcl,
        channelAclPassword = channelAclPassword,
        permissionEpoch = permissionEpoch,
        showUserCount = showUserCount,
        onUserInformation = onUserInformation,
        homeAllLinks = homeAllLinks,
        homeDirectLinks = homeDirectLinks,
        onShoutToChannel = onShoutToChannel,
        onWhisperToUser = onWhisperToUser,
        onStopVoiceTarget = onStopVoiceTarget,
        activeShoutChannelId = activeShoutChannelId,
        whisperSessions = whisperSessions,
        mayWhisper = mayWhisper,
        loopback = loopback,
        onSetLoopback = onSetLoopback,
        onWhisperToUsers = onWhisperToUsers,
        onShoutToChannelPicker = onShoutToChannelPicker,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            ChannelNode(
                channel = channel,
                indent = 0,
                collapsedIds = collapsed,
                onToggleCollapsed = {
                    collapsedIds = ChannelTree.toggleCollapsed(collapsed, it).toList()
                },
                actions = actions,
            )
        }
    }
}
