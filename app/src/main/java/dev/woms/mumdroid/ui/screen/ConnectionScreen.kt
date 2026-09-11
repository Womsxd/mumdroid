package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.ui.ConnectionState
import dev.woms.mumdroid.ui.SessionCommands

/**
 * The in-connection screen showing channels/users, voice controls and chat.
 *
 * The screen is an orchestration: the app bar, the two panels and the dialogs
 * each live in their own file, the transient surface state in
 * [ConnectionScreenState], and the pure decisions (local channel, chat
 * destination, voice-bar visibility) in [ConnectionScreenLogic].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: ConnectionState,
    commands: SessionCommands,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    showUserCount: Boolean = false,
    voiceMode: VoiceMode = VoiceMode.CONTINUOUS,
) {
    val screen = rememberConnectionScreenState()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showVoiceControls = ConnectionScreenLogic.showVoiceControls(screen.tab, keyboardOpen)
    val localChannelId = ConnectionScreenLogic.localChannelId(state.users)
    val chatChannelId = ConnectionScreenLogic.chatChannelId(state.users, state.channels)
    val moveChannels = ChannelTree.flattenForPicker(state.channels)
    val joinById = remember(state.channels, commands) {
        { channelId: Int ->
            val channel = ChannelTree.find(state.channels, channelId)
            if (channel != null && channel.isEnterRestricted && !channel.canEnter) {
                screen.localPasswordPrompt = ChannelPasswordPrompt(channel.id, channel.name)
            } else {
                commands.joinChannel(channelId)
            }
        }
    }
    val onJoinChannelFromList = remember(joinById) {
        { channel: Channel -> joinById(channel.id) }
    }
    val onUserInformation = remember(commands, screen) {
        { userSession: Int, name: String ->
            if (screen.infoSession != userSession) commands.clearUserStats()
            screen.openUserInformation(userSession, name)
            commands.requestUserStats(userSession, false)
        }
    }
    val onShowServerInfo = remember { { screen.showServerInfo = true } }
    val onShowAccessTokens = remember { { screen.showAccessTokens = true } }
    val onOpenRegisteredUsers = remember(commands, screen) {
        {
            commands.requestUserList()
            screen.adminPage = AdminPage.RegisteredUsers
        }
    }
    val onOpenBanList = remember(commands, screen) {
        {
            commands.requestBanList()
            screen.adminPage = AdminPage.BanList
        }
    }

    LaunchedEffect(state.connected) {
        if (state.connected) commands.ensureChannelPermissions(0)
    }

    val voiceTarget = state.voiceTarget
    val activeShoutChannelId = (voiceTarget.spec as? VoiceTargetSpec.Channel)?.channelId
    val whisperSessions = (voiceTarget.spec as? VoiceTargetSpec.Users)?.sessions?.toSet() ?: emptySet()
    val onWhisperToUser = remember(commands) {
        { session: Int -> commands.setVoiceTarget(VoiceTargetSpec.Users(listOf(session))) }
    }

    when (screen.adminPage) {
        AdminPage.RegisteredUsers -> {
            RegisteredUsersScreen(
                users = state.registeredUsers,
                channels = state.channels,
                isRefreshing = state.userListRefreshing,
                onBack = { screen.adminPage = null },
                onRename = commands::renameRegisteredUser,
                onRemove = commands::unregisterUser,
                onRefresh = { commands.requestUserList(clear = false) },
            )
            return
        }
        AdminPage.BanList -> {
            BanListScreen(
                bans = state.banList,
                isRefreshing = state.banListRefreshing,
                onBack = { screen.adminPage = null },
                onReplace = commands::replaceBanList,
                onRefresh = { commands.requestBanList(clear = false) },
            )
            return
        }
        null -> Unit
    }

    ConnectionScreenDialogs(
        state = state,
        commands = commands,
        screen = screen,
        moveChannels = moveChannels,
    )

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            ConnectionTopBar(
                serverName = state.serverName,
                connected = state.connected,
                canEditRegisteredUsers = commands.canEditRegisteredUsers(),
                canBan = commands.canBanUser(),
                onBack = onBack,
                onDisconnect = onDisconnect,
                onShowServerInfo = onShowServerInfo,
                onShowAccessTokens = onShowAccessTokens,
                onOpenRegisteredUsers = onOpenRegisteredUsers,
                onOpenBanList = onOpenBanList,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        bottomBar = {
            if (showVoiceControls) {
                VoiceControlBar(
                    selfMuted = state.selfMuted,
                    selfDeafened = state.selfDeafened,
                    onToggleMute = commands::toggleSelfMute,
                    onToggleDeafen = commands::toggleSelfDeafen,
                    onTalkStart = commands::startTalking,
                    onTalkStop = commands::stopTalking,
                    voiceMode = voiceMode,
                    outputTarget = state.outputTarget,
                    onSelectOutputTarget = commands::setOutputTarget,
                    voiceTarget = voiceTarget,
                    loopback = state.loopbackMode,
                    onClearVoiceTarget = commands::clearVoiceTarget,
                    onSetLoopback = commands::setLoopback,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = screen.tab) {
                Tab(
                    selected = screen.tab == ConnectionScreenLogic.TAB_CHANNELS,
                    onClick = { screen.tab = ConnectionScreenLogic.TAB_CHANNELS },
                    text = { Text(stringResource(R.string.channels)) },
                )
                Tab(
                    selected = screen.tab == ConnectionScreenLogic.TAB_CHAT,
                    onClick = { screen.tab = ConnectionScreenLogic.TAB_CHAT },
                    text = { Text(stringResource(R.string.chat)) },
                )
            }

            when (screen.tab) {
                ConnectionScreenLogic.TAB_CHANNELS -> ChannelList(
                    channels = state.channels,
                    users = state.users,
                    onJoinChannel = onJoinChannelFromList,
                    onJoinUserChannel = joinById,
                    onMoveUser = commands::moveUser,
                    localChannelId = localChannelId,
                    moveChannels = moveChannels,
                    onSetLocalBlock = commands::setLocalBlock,
                    onSetLocalIgnore = commands::setLocalIgnore,
                    onSetRemoteMute = commands::setRemoteMute,
                    onSetRemoteDeafen = commands::setRemoteDeafen,
                    onSetPrioritySpeaker = commands::setPrioritySpeaker,
                    onKickUser = commands::kickUser,
                    onBanUser = commands::banUser,
                    onRegisterUser = commands::registerUser,
                    canAdministerChannel = commands::canAdministerChannel,
                    canMuteUser = commands::canMuteUser,
                    canPrioritySpeaker = commands::canPrioritySpeaker,
                    canMoveInChannel = commands::canMoveInChannel,
                    onQueryChannelPermissions = commands::ensureChannelPermissions,
                    canKickUser = commands::canKickUser,
                    canBanUser = commands::canBanUser,
                    canRegisterUser = commands::canRegisterUser,
                    supportsSelectiveBan = commands::supportsSelectiveBan,
                    canTextMessage = commands::canTextMessage,
                    canListen = commands::canListen,
                    supportsChannelListen = commands::supportsChannelListen,
                    listeningChannels = state.listeningChannels,
                    onSendChat = commands::sendChat,
                    onSendPrivateChat = commands::sendPrivateChat,
                    onSetChannelListening = commands::setChannelListening,
                    canWriteChannel = commands::canWriteChannel,
                    canAddChannel = commands::canAddChannel,
                    canMakePermanentChannel = commands::canMakePermanentChannel,
                    canLinkChannel = commands::canLinkChannel,
                    onLinkChannel = commands::linkChannel,
                    onUnlinkChannel = commands::unlinkChannel,
                    onUnlinkAllChannels = commands::unlinkAllChannels,
                    onCreateChannel = commands::createChannel,
                    onUpdateChannel = commands::updateChannel,
                    onRemoveChannel = commands::removeChannel,
                    onRequestChannelDescription = commands::requestChannelDescription,
                    onRequestChannelAcl = commands::requestChannelAcl,
                    channelAclPassword = state.channelAclPassword,
                    permissionEpoch = state.permissionEpoch,
                    showUserCount = showUserCount,
                    onUserInformation = onUserInformation,
                    onWhisperToUser = onWhisperToUser,
                    onStopVoiceTarget = commands::clearVoiceTarget,
                    whisperSessions = whisperSessions,
                    mayWhisper = commands::mayWhisper,
                    onShoutToChannel = { channelId, links, children, group ->
                        commands.setVoiceTarget(
                            VoiceTargetSpec.Channel(channelId, links, children, group),
                        )
                    },
                    activeShoutChannelId = activeShoutChannelId,
                    loopback = state.loopbackMode,
                    onSetLoopback = commands::setLoopback,
                    onWhisperToUsers = { screen.whisperPicker = true },
                    onShoutToChannelPicker = { screen.shoutPicker = true },
                )
                ConnectionScreenLogic.TAB_CHAT -> ChatPanel(
                    messages = state.chatMessages,
                    users = state.users,
                    channels = state.channels,
                    channelId = chatChannelId,
                    onSend = commands::sendChat,
                    onSendPrivate = commands::sendPrivateChat,
                )
            }
        }
    }
}
