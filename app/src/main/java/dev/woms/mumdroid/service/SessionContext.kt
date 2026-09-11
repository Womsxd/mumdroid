package dev.woms.mumdroid.service

import kotlinx.coroutines.CoroutineScope

/**
 * The collaborators a session's protocol event handler needs, bundled so
 * [MumbleServiceEvents] does not grow a constructor parameter per collaborator
 * (it reached fourteen before this).
 *
 * Assembled by [MumbleService] in `onCreate`, once every member exists; the
 * host callbacks (strings, persistence, connect, stopSelf) stay separate
 * because they go back into the owning Service.
 */
internal class SessionContext(
    val state: SessionState,
    val scope: CoroutineScope,
    val roster: SessionRoster,
    val admin: ServerAdminSession,
    val voice: VoiceSession,
    val chat: SessionChat,
    val notices: SessionNotices,
    val reconnect: ReconnectController,
    val cert: CertificatePromptController,
    val lastChannel: LastChannelSession,
    val sessionChannels: SessionChannels,
    val tcpPing: TcpPingStats,
    val notifications: ConnectionNotifications,
)
