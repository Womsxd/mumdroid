package dev.woms.mumdroid.ui

import dev.woms.mumdroid.core.model.CertificatePrompt
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.RegisteredUser

/** Low-frequency session snapshot for the UI.
 *
 *  Audio-callback meters (VAD level, local PTT/VAD talking flag) are
 *  deliberately omitted: they change every capture frame and must not
 *  rebuild this object. Roster talking icons travel with [ConnectionState.users].
 */
data class ConnectionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "",
    val serverName: String = "",
    val channels: List<dev.woms.mumdroid.core.model.Channel> = emptyList(),
    val users: List<dev.woms.mumdroid.core.model.User> = emptyList(),
    val selfMuted: Boolean = false,
    val selfDeafened: Boolean = false,
    val chatMessages: List<dev.woms.mumdroid.core.model.ChatMessage> = emptyList(),
    /** Seconds until the next automatic reconnect after a drop; 0 if none. */
    val reconnectCountdown: Int = 0,
    /** True from an unexpected drop until we are back or retries are exhausted. */
    val reconnecting: Boolean = false,
    /** Live snapshot of the connected server (empty while disconnected). */
    val serverInfo: ServerConnectionInfo = ServerConnectionInfo(),
    val userInfo: UserConnectionInfo? = null,
    val channelPasswordPrompt: ChannelPasswordPrompt? = null,
    /** Certificate-mismatch prompt while the TLS handshake waits for the user. */
    val certificatePrompt: CertificatePrompt? = null,
    /** Local user's access-token bag for this server (desktop `tokens`). */
    val accessTokens: List<String> = emptyList(),
    val registeredUsers: List<RegisteredUser>? = null,
    val banList: List<BanEntry>? = null,
    val userListRefreshing: Boolean = false,
    val banListRefreshing: Boolean = false,
    /** Incremented when channel ACL bits arrive; menus re-check mute/move. */
    val permissionEpoch: Int = 0,
    /** Set when the server kicked/banned/removed us; not a network drop. */
    val serverRemoval: ServerRemoval? = null,
    /** Device currently playing incoming voice; null while disconnected. */
    val outputTarget: VoiceOutputTarget? = null,
    /** Channel ids the local user is listening to without joining. */
    val listeningChannels: Set<Int> = emptySet(),
    /** Password from the last ACL query, used by the channel-edit dialog. */
    val channelAclPassword: ChannelAclPassword? = null,
    /** Saved favorite this session was started from (0 if unknown). */
    val favoriteId: Long = 0,
) {
    /**
     * `host:port` of the live session, used to mark the matching home-list
     * card when the favorite id is unknown. Null when no connect is in flight.
     */
    val activeServerKey: String?
        get() {
            if (!connected && !connecting && !reconnecting) return null
            val host = serverInfo.host.trim()
            if (host.isEmpty() || serverInfo.port <= 0) return null
            return "${host.lowercase()}:${serverInfo.port}"
        }

    /** Favorite row to highlight; null when idle. */
    val activeServerId: Long?
        get() {
            if (!connected && !connecting && !reconnecting) return null
            return favoriteId.takeIf { it > 0L }
        }
}
