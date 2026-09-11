package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UdpVoiceManager
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
 *
 * The two companions the read surface needs live in this file: [SessionVoiceStatus]
 * (the slice of [VoiceSession] a snapshot may touch without an Android
 * `AudioManager`) and [buildServerConnectionInfo] (the snapshot itself). Both are
 * only reachable through this facade, so a separate file each added a hop
 * without a boundary.
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

/**
 * The voice state a session snapshot needs, without the audio endpoints.
 *
 * Implemented by [VoiceSession] so [SessionFacade] and
 * [buildServerConnectionInfo] can be unit-tested without an Android
 * `AudioManager` (the real session's route controller needs one).
 */
internal interface SessionVoiceStatus {
    /** Active audio self-test mode (off / local / server). */
    val loopbackMode: StateFlow<LoopbackMode>

    /** Active shout/whisper target, or a regular-speech status. */
    val voiceTarget: StateFlow<VoiceTargetStatus>

    val outputTarget: StateFlow<VoiceOutputTarget?>

    val selfMuted: StateFlow<Boolean>

    val selfDeafened: StateFlow<Boolean>

    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    val serverMaxBandwidthBps: Int

    /** The UDP voice channel, or null in force-TCP mode. */
    val udp: UdpVoiceManager?

    /** Effective transmit bandwidth of the current configuration. */
    fun currentBandwidthBps(): Int

    /** Whether voice is currently tunneled over TCP. */
    fun udpFallback(forceTcp: Boolean, live: Boolean): Boolean
}

/** Assembles the desktop-style server information snapshot. */
internal fun buildServerConnectionInfo(
    live: Boolean,
    host: String,
    port: Int,
    userCount: Int,
    maxUsers: Int,
    client: MumbleClient?,
    voice: SessionVoiceStatus,
    tcp: TcpPingStats,
    forceTcp: Boolean,
): ServerConnectionInfo {
    val c = if (live) client else null
    val udp = if (live) voice.udp else null
    val local = udp?.packetStats()
    val tcpAvg = if (live) tcp.averageMs else 0f
    val tcpVar = if (live) tcp.variance else 0f
    val tls = ServerConnectionInfo.formatTlsProtocol(c?.tlsProtocol.orEmpty())
    val cipher = c?.tlsCipherSuite.orEmpty()
    return ServerConnectionInfo(
        host = host,
        port = port,
        userCount = if (live) userCount else 0,
        maxUsers = if (live) maxUsers else 0,
        protocol = ServerConnectionInfo.formatProtocol(
            c?.serverVersionV2 ?: 0L,
            c?.serverVersionLegacy ?: 0,
        ),
        release = c?.serverRelease.orEmpty(),
        os = c?.serverOs.orEmpty(),
        osVersion = c?.serverOsVersion.orEmpty(),
        currentBandwidthBps = voice.currentBandwidthBps(),
        allowedBandwidthBps = if (live) voice.serverMaxBandwidthBps else 0,
        forceTcp = forceTcp,
        udpFallback = voice.udpFallback(forceTcp, live),
        hasUdpLatency = (udp?.udpPingCount ?: 0) > 0,
        udpLatencyMs = (udp?.averageUdpPing ?: 0L).toFloat(),
        udpLatencyVariance = udp?.udpPingVariance ?: 0f,
        udpGoodIn = local?.good ?: 0,
        udpLateIn = local?.late ?: 0,
        udpLostIn = local?.lost ?: 0,
        udpResyncIn = local?.resync ?: 0,
        udpGoodOut = c?.remoteCryptGood ?: 0,
        udpLateOut = c?.remoteCryptLate ?: 0,
        udpLostOut = c?.remoteCryptLost ?: 0,
        udpResyncOut = c?.remoteCryptResync ?: 0,
        tlsVersion = tls,
        cipherSuite = cipher,
        hasTcpLatency = live && tcp.sampleCount > 0,
        tcpLatencyMs = tcpAvg,
        tcpLatencyVariance = tcpVar,
        perfectForwardSecrecy = c?.let {
            if (it.tlsProtocol.isEmpty() && it.tlsCipherSuite.isEmpty()) {
                null
            } else {
                ServerConnectionInfo.usesPerfectForwardSecrecy(it.tlsProtocol, it.tlsCipherSuite)
            }
        },
        certificateFingerprint = c?.serverFingerprint.orEmpty(),
    )
}
