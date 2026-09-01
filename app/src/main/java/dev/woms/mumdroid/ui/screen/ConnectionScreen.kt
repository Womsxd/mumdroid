package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.ui.ConnectionState
import kotlinx.coroutines.delay

/**
 * The in-connection screen showing channels/users, voice controls and chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: ConnectionState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onJoinChannel: (Int) -> Unit,
    onMoveUser: (Int, Int) -> Unit,
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
    onSendChat: (Int, String) -> Unit,
    onSendPrivateChat: (Int, String) -> Unit,
    canTextMessage: (Int) -> Boolean = { false },
    canListen: (Int) -> Boolean = { false },
    supportsChannelListen: () -> Boolean = { false },
    onSetChannelListening: (Int, Boolean) -> Unit = { _, _ -> },
    canWriteChannel: (Int) -> Boolean = { false },
    canAddChannel: (Int) -> Boolean = { false },
    canMakePermanentChannel: (Int) -> Boolean = { false },
    canLinkChannel: (Int) -> Boolean = { false },
    onLinkChannel: (Int) -> Unit = {},
    onUnlinkChannel: (Int) -> Unit = {},
    onUnlinkAllChannels: () -> Unit = {},
    onCreateChannel: (Int, String, String, Int, Boolean, Int, String) -> Unit = { _, _, _, _, _, _, _ -> },
    onUpdateChannel: (Int, String, String, Int, Int, String) -> Unit = { _, _, _, _, _, _ -> },
    onRemoveChannel: (Int) -> Unit = {},
    onRequestChannelDescription: (Int) -> Unit = {},
    onRequestChannelAcl: (Int) -> Unit = {},
    showUserCount: Boolean = false,
    onTalkStart: () -> Unit = {},
    onTalkStop: () -> Unit = {},
    voiceMode: VoiceMode = VoiceMode.CONTINUOUS,
    outputTarget: VoiceOutputTarget? = null,
    onSelectOutputTarget: (VoiceOutputTarget) -> Unit = {},
    onRequestUserStats: (Int, Boolean) -> Unit = { _, _ -> },
    onClearUserStats: () -> Unit = {},
    onJoinChannelWithPassword: (Int, String) -> Unit = { _, _ -> },
    onClearChannelPasswordPrompt: () -> Unit = {},
    onUpdatePinnedCertificate: () -> Unit = {},
    onTrustCertificateOnce: () -> Unit = {},
    onRejectCertificate: () -> Unit = {},
    onReplaceAccessTokens: (List<String>) -> Unit = {},
    canEditRegisteredUsers: () -> Boolean = { false },
    onRequestUserList: () -> Unit = {},
    onRenameRegisteredUser: (Int, String) -> Unit = { _, _ -> },
    onUnregisterUser: (Int) -> Unit = {},
    onRefreshUserList: () -> Unit = {},
    onRequestBanList: () -> Unit = {},
    onReplaceBanList: (List<BanEntry>) -> Unit = {},
    onRefreshBanList: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var showServerInfo by remember { mutableStateOf(false) }
    var showAccessTokens by remember { mutableStateOf(false) }
    var adminPage by remember { mutableStateOf<AdminPage?>(null) }
    var infoSession by remember { mutableStateOf<Int?>(null) }
    var infoUserName by remember { mutableStateOf("") }
    var localPasswordPrompt by remember { mutableStateOf<ChannelPasswordPrompt?>(null) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showVoiceControls = tab != 1 || !keyboardOpen
    val localChannelId = state.users.firstOrNull { it.isLocalUser }?.channelId ?: 0
    val moveChannels = ChannelTree.flattenForPicker(state.channels)
    val joinById = remember(state.channels, onJoinChannel) {
        { channelId: Int ->
            val channel = ChannelTree.find(state.channels, channelId)
            if (channel != null && channel.isEnterRestricted && !channel.canEnter) {
                localPasswordPrompt = ChannelPasswordPrompt(channel.id, channel.name)
            } else {
                onJoinChannel(channelId)
            }
        }
    }
    val onJoinChannelFromList = remember(joinById) {
        { channel: Channel -> joinById(channel.id) }
    }
    val onUserInformation = remember(onClearUserStats, onRequestUserStats) {
        { session: Int, name: String ->
            if (infoSession != session) onClearUserStats()
            infoUserName = name
            infoSession = session
            onRequestUserStats(session, false)
        }
    }
    val onShowServerInfo = remember { { showServerInfo = true } }
    val onShowAccessTokens = remember { { showAccessTokens = true } }
    val onOpenRegisteredUsers = remember(onRequestUserList) {
        {
            onRequestUserList()
            adminPage = AdminPage.RegisteredUsers
        }
    }
    val onOpenBanList = remember(onRequestBanList) {
        {
            onRequestBanList()
            adminPage = AdminPage.BanList
        }
    }

    LaunchedEffect(state.connected) {
        if (state.connected) onQueryChannelPermissions(0)
    }

    when (adminPage) {
        AdminPage.RegisteredUsers -> {
            RegisteredUsersScreen(
                users = state.registeredUsers,
                channels = state.channels,
                isRefreshing = state.userListRefreshing,
                onBack = { adminPage = null },
                onRename = onRenameRegisteredUser,
                onRemove = onUnregisterUser,
                onRefresh = onRefreshUserList,
            )
            return
        }
        AdminPage.BanList -> {
            BanListScreen(
                bans = state.banList,
                isRefreshing = state.banListRefreshing,
                onBack = { adminPage = null },
                onReplace = onReplaceBanList,
                onRefresh = onRefreshBanList,
            )
            return
        }
        null -> Unit
    }

    val passwordPrompt = state.channelPasswordPrompt ?: localPasswordPrompt
    if (passwordPrompt != null) {
        ChannelPasswordDialog(
            prompt = passwordPrompt,
            onSubmit = { token ->
                localPasswordPrompt = null
                onJoinChannelWithPassword(passwordPrompt.channelId, token)
            },
            onDismiss = {
                localPasswordPrompt = null
                onClearChannelPasswordPrompt()
            },
        )
    }

    // Certificate pinning mismatch: the TLS handshake is paused until the
    // user updates the pin, trusts the certificate once, or rejects.
    state.certificatePrompt?.let { prompt ->
        CertificatePromptDialog(
            prompt = prompt,
            onUpdatePin = onUpdatePinnedCertificate,
            onTrustOnce = onTrustCertificateOnce,
            onReject = onRejectCertificate,
        )
    }

    if (showServerInfo && state.connected) {
        ServerInformationDialog(
            info = state.serverInfo,
            onDismiss = { showServerInfo = false },
        )
    }

    if (showAccessTokens && state.connected) {
        AccessTokensDialog(
            tokens = state.accessTokens,
            onReplace = onReplaceAccessTokens,
            onDismiss = { showAccessTokens = false },
        )
    }

    val viewingSession = infoSession
    if (viewingSession != null) {
        LaunchedEffect(viewingSession) {
            while (true) {
                delay(6_000)
                onRequestUserStats(viewingSession, true)
            }
        }
        UserInformationDialog(
            userName = infoUserName,
            info = state.userInfo?.takeIf { it.session == viewingSession },
            onDismiss = {
                infoSession = null
                infoUserName = ""
                onClearUserStats()
            },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            ConnectionTopBar(
                serverName = state.serverName,
                connected = state.connected,
                canEditRegisteredUsers = canEditRegisteredUsers(),
                canBan = canBanUser(),
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
                    onToggleMute = onToggleMute,
                    onToggleDeafen = onToggleDeafen,
                    onTalkStart = onTalkStart,
                    onTalkStop = onTalkStop,
                    voiceMode = voiceMode,
                    outputTarget = outputTarget,
                    onSelectOutputTarget = onSelectOutputTarget,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.channels)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.chat)) })
            }

            when (tab) {
                0 -> ChannelList(
                    channels = state.channels,
                    users = state.users,
                    onJoinChannel = onJoinChannelFromList,
                    onJoinUserChannel = joinById,
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
                    listeningChannels = state.listeningChannels,
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
                    channelAclPassword = state.channelAclPassword,
                    permissionEpoch = state.permissionEpoch,
                    showUserCount = showUserCount,
                    onUserInformation = onUserInformation,
                )
                1 -> ChatPanel(
                    messages = state.chatMessages,
                    users = state.users,
                    channels = state.channels,
                    // Send to the channel the local user is currently in, not
                    // merely the first channel in the tree. Otherwise messages
                    // go to the wrong channel and appear to never be sent.
                    channelId = state.users.firstOrNull { it.isLocalUser }?.channelId
                        ?: state.channels.firstOrNull()?.id ?: 0,
                    onSend = onSendChat,
                    onSendPrivate = onSendPrivateChat,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTopBar(
    serverName: String,
    connected: Boolean,
    canEditRegisteredUsers: Boolean,
    canBan: Boolean,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onShowServerInfo: () -> Unit,
    onShowAccessTokens: () -> Unit,
    onOpenRegisteredUsers: () -> Unit,
    onOpenBanList: () -> Unit,
) {
    var showServerMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(serverName.ifEmpty { stringResource(R.string.connection) }) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onShowServerInfo,
                enabled = connected,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.server_information),
                )
            }
            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = stringResource(R.string.disconnect),
                )
            }
            Box {
                IconButton(
                    onClick = { showServerMenu = true },
                    enabled = connected,
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.server_menu),
                    )
                }
                DropdownMenu(
                    expanded = showServerMenu,
                    onDismissRequest = { showServerMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.access_tokens)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Key, contentDescription = null)
                        },
                        onClick = {
                            showServerMenu = false
                            onShowAccessTokens()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.registered_users)) },
                        leadingIcon = {
                            Icon(Icons.Filled.HowToReg, contentDescription = null)
                        },
                        enabled = canEditRegisteredUsers,
                        onClick = {
                            showServerMenu = false
                            onOpenRegisteredUsers()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ban_list)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Block, contentDescription = null)
                        },
                        enabled = canBan,
                        onClick = {
                            showServerMenu = false
                            onOpenBanList()
                        },
                    )
                }
            }
        },
    )
}

private enum class AdminPage {
    RegisteredUsers,
    BanList,
}

