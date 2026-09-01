package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.proto.UserState

/** A single entry in a server ban list. */
data class BanEntry(
    val address: ByteArray = byteArrayOf(),
    val mask: Int = 0,
    val name: String = "",
    val hash: String = "",
    val reason: String = "",
    val start: String = "",
    val duration: Int = 0,
)

/** A registered (database) user reported by the server. */
data class RegisteredUser(
    val userId: Int = 0,
    val name: String = "",
    val lastSeen: String = "",
    val lastChannel: Int = 0,
)

/**
 * The user's decision when the TLS handshake hit a certificate problem while
 * certificate pinning was enabled.
 */
enum class CertificateDecision {
    /** Re-pin the presented certificate and continue (replaces the stored pin). */
    UPDATE_PIN,

    /** Accept the certificate for this session only; the stored pin is kept. */
    TRUST_ONCE,

    /** Abort the connection. */
    REJECT,
}

/**
 * Callbacks delivered by [MumbleClient] as the connection progresses and
 * server state changes arrive.
 */
interface MumbleListener {
    /** The client connected and authenticated. [session] is our local session id. */
    fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int = 0)

    /** The server rejected the connection. */
    fun onRejected(reason: String, type: Int)

    /** The connection was lost (network error or server close). */
    fun onDisconnected(reason: String)

    /**
     * The TLS handshake hit a certificate problem while certificate pinning
     * was enabled: the presented fingerprint does not match the pinned one.
     *
     * The handshake thread is paused until [respond] is invoked with the
     * user's [CertificateDecision] (usually after showing a dialog). The
     * default rejects the connection.
     *
     * @param fingerprint the SHA-256 fingerprint the server presented.
     * @param pinnedFingerprint the fingerprint previously pinned for this
     *   server (the one the user is being asked to replace or keep).
     */
    fun onCertificateError(
        fingerprint: String,
        pinnedFingerprint: String,
        respond: (CertificateDecision) -> Unit,
    ) {
        respond(CertificateDecision.REJECT)
    }

    /** A channel was added or updated. */
    fun onChannelState(channel: Channel)

    /** Raw ChannelState so the service can merge unset protobuf fields. */
    fun onChannelStateProto(state: dev.woms.mumdroid.core.proto.ChannelState) {
        onChannelState(
            Channel(
                id = state.channelId,
                parentId = state.parent,
                name = state.name,
                description = state.description,
                position = state.position,
                temporary = state.temporary,
                maxUsers = if (state.hasMaxUsers()) state.maxUsers else 0,
                isEnterRestricted = if (state.hasIsEnterRestricted()) state.isEnterRestricted else false,
                canEnter = if (state.hasCanEnter()) state.canEnter else true,
                linkedIds = state.linksList.toSet(),
            )
        )
    }

    /** A channel was removed. */
    fun onChannelRemoved(channelId: Int)

    /** A user was added or updated. */
    fun onUserState(user: UserState)

    /**
     * A user left, or was kicked/banned.
     *
     * When [session] is the local user this is a server-forced disconnect,
     * not a network drop. [hasActor] follows the protobuf field: a kick/ban
     * always sets it; a normal leave or ghost login does not.
     */
    fun onUserRemoved(
        session: Int,
        actor: Int = 0,
        hasActor: Boolean = false,
        reason: String = "",
        ban: Boolean = false,
    )

    /** An incoming text message. [isPrivate] is true when it is a direct message (session target) rather than a channel broadcast. */
    fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean = false)

    /** A permission denial or other informational message from the server. */
    fun onInfo(message: String)

    /** Received the UDP encryption key material (key + client/server nonces). */
    fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray)

    /**
     * The server reported its protocol version in its Version message.
     * [versionV2] is the v2-format version (0 when absent) and [legacyVersion]
     * the legacy 16.8.8 packed version; together they decide the negotiated
     * UDP framing.
     */
    fun onServerVersion(versionV2: Long, legacyVersion: Int) {}

    /**
     * Received a raw voice packet tunneled over the TCP control channel
     * (force-TCP mode). [body] is the plaintext packet in the negotiated
     * framing (no OCB2 decryption is needed over TCP); decoding happens in the
     * owner of the voice channel state.
     */
    fun onTunneledPacket(body: ByteArray) {}

    // ---- Additional protocol messages (handled server->client) ----

    /** Server reported the current ban list. */
    fun onBanList(bans: List<BanEntry>, query: Boolean) {}

    /** Server returned the channel ACL / group listing. */
    fun onAcl(acl: dev.woms.mumdroid.core.proto.ACL) {}

    /** Server reply to a QueryUsers request (id<->name mapping). */
    fun onQueryUsers(ids: List<Int>, names: List<String>) {}

    /** Server registered/removed a context-menu action. */
    fun onContextActionModify(action: String, text: String, context: Int, operation: Int) {}

    /** A context-menu action was invoked by a user. */
    fun onContextAction(session: Int, channelId: Int, action: String) {}

    /** Server reported the registered-user list. */
    fun onUserList(users: List<RegisteredUser>) {}

    /** Server acknowledged a voice-target change. */
    fun onVoiceTarget(id: Int) {}

    /** Server reported a user's permissions in a channel. */
    fun onPermissionQuery(channelId: Int, permissions: Int, flush: Boolean) {}

    /** Server reported connection quality / stats for a user (`UserStats`). */
    fun onUserStats(stats: dev.woms.mumdroid.core.proto.UserStats) {}

    /** Server denied an action. */
    fun onPermissionDenied(denied: dev.woms.mumdroid.core.proto.PermissionDenied) {
        onInfo(denied.reason.ifEmpty { "Permission denied" })
    }

    /** Server announced the voice codec. We only speak Opus. */
    fun onCodecVersion(opus: Boolean) {}

    /** Server asked us to upload missing comment/description blobs. */
    fun onRequestBlob(
        sessionTexture: List<Int>,
        sessionComment: List<Int>,
        channelDescription: List<Int>,
    ) {}

    /** Server suggested client configuration. */
    fun onSuggestConfig(positional: Boolean, pushToTalk: Boolean) {}

    /**
     * Server configuration after sync. [welcomeText] is empty when the field
     * was omitted; do not treat that as a connection-status change.
     */
    fun onServerConfig(welcomeText: String, maxBandwidth: Int, maxUsers: Int = 0) {}
}
