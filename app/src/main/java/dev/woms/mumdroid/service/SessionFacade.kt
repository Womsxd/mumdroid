package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.net.AclUserNames
import kotlinx.coroutines.flow.StateFlow

/**
 * The read surface of a running session: every StateFlow the UI mirrors plus the
 * derived connection snapshot.
 *
 * [MumbleService] used to expose all of these as hand-written getters that
 * forwarded to `state` / `roster` / `admin` / `voice` / `chat` / `reconnect` /
 * `cert`. Collecting them here keeps the service an aggregation root for the
 * *lifecycle* while a single object owns the delegation table.
 *
 * Constructed in [MumbleService.onCreate], once every collaborator exists; the
 * binding UI only reads it after the service is connected, so none of the
 * references can be observed before they are set.
 */
internal class SessionFacade(
    private val state: SessionState,
    private val roster: SessionRoster,
    private val admin: ServerAdminSession,
    private val voice: SessionVoiceStatus,
    private val chat: SessionChat,
    private val reconnect: ReconnectController,
    private val cert: CertificatePromptController,
    private val tcpPing: TcpPingStats,
) {
    // ---- connection ----
    val connected: StateFlow<Boolean> = state.connected
    val connecting: StateFlow<Boolean> = state.connecting
    val status: StateFlow<String> = state.status
    val serverName: StateFlow<String> = state.serverName
    val serverRemoval: StateFlow<ServerRemoval?> = state.serverRemoval

    // ---- roster ----
    val channels: StateFlow<List<Channel>> = roster.channels
    val users: StateFlow<List<User>> = roster.users
    val permissionEpoch: StateFlow<Int> = roster.permissionEpoch
    val listeningChannels: StateFlow<Set<Int>> = roster.listeningChannels

    // ---- voice ----
    val outputTarget: StateFlow<VoiceOutputTarget?> = voice.outputTarget
    val voiceTarget: StateFlow<VoiceTargetStatus> = voice.voiceTarget
    val loopbackMode: StateFlow<LoopbackMode> = voice.loopbackMode
    val selfMuted: StateFlow<Boolean> = voice.selfMuted
    val selfDeafened: StateFlow<Boolean> = voice.selfDeafened

    // ---- chat / reconnect ----
    val chatMessages = chat.messages
    val reconnectCountdown: StateFlow<Int> = reconnect.countdown
    val reconnecting: StateFlow<Boolean> = reconnect.reconnecting

    // ---- admin ----
    val userStats = admin.userStats
    val channelPasswordPrompt = admin.channelPasswordPrompt
    val accessTokens = admin.accessTokens
    val registeredUsers = admin.registeredUsers
    val banList = admin.banList
    val userListRefreshing = admin.userListRefreshing
    val banListRefreshing = admin.banListRefreshing
    val channelAclPassword = admin.channelAclPassword
    val aclUserNames: StateFlow<AclUserNames> = admin.aclUserNames
    val channelAcl = admin.channelAcl

    /** Certificate-mismatch prompt while the TLS handshake waits for the user. */
    val certificatePrompt = cert.prompt

    /** Id of the last/current server, for favouriting from the session screen. */
    fun favoriteId(): Long = state.lastConnectParams?.serverId ?: state.connectedServerId

    /** Desktop-style snapshot of the live connection for the info dialog. */
    fun connectionInfo(): ServerConnectionInfo = buildServerConnectionInfo(
        live = state.connected.value,
        host = state.host,
        port = state.port,
        userCount = roster.userMap.size,
        maxUsers = state.serverMaxUsers,
        client = state.client,
        voice = voice,
        tcp = tcpPing,
        forceTcp = state.forceTcp,
    )


}
