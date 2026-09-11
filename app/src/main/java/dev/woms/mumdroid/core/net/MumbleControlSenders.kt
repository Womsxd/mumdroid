package dev.woms.mumdroid.core.net

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.RegisteredUser
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.Authenticate
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.ChannelState
import dev.woms.mumdroid.core.proto.CryptSetup
import dev.woms.mumdroid.core.proto.PermissionQuery
import dev.woms.mumdroid.core.proto.QueryUsers
import dev.woms.mumdroid.core.proto.RequestBlob
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.UserList
import dev.woms.mumdroid.core.proto.UserState
import dev.woms.mumdroid.core.proto.UserStats

/**
 * The typed sender API of the Mumble control channel — the official
 * `ServerHandler` surface. Implemented by [MumbleControlSenders] and mixed into
 * [MumbleClient] through interface delegation, so callers keep talking to
 * `client.kickUser(...)` while the message assembly lives here.
 */
internal interface MumbleControlSender {
    /** Sends a text message to a channel. */
    fun sendTextToChannel(channelId: Int, text: String)

    /** Sends a private (direct) text message to a specific user session. */
    fun sendTextToUser(session: Int, text: String)

    /** Desktop `ServerHandler::kickUser`: `UserRemove` with `ban = false`. */
    fun kickUser(session: Int, reason: String)

    /**
     * Desktop `ServerHandler::banUser`: `UserRemove` with `ban = true` and the
     * 1.6+ certificate/IP flags.
     */
    fun banUser(session: Int, reason: String, banCertificate: Boolean, banIp: Boolean)

    /** Desktop `ServerHandler::registerUser`: `UserState` with `user_id = 0`. */
    fun registerUser(session: Int)

    /** Moves the local user to [channelId]. */
    fun joinChannel(channelId: Int, temporaryAccessTokens: List<String> = emptyList())

    /** Desktop `ServerHandler::joinChannel` targeting another user's session. */
    fun moveUser(session: Int, channelId: Int)

    /** Desktop `ServerHandler::startListeningToChannel` / `stopListeningToChannel`. */
    fun setChannelListening(channelId: Int, listen: Boolean)

    /** Desktop `ServerHandler::createChannel`: ChannelState without `channel_id`. */
    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
    )

    /** Desktop `ACLEditor::accept` update path; no-op when [msg] is null. */
    fun updateChannel(msg: ChannelState?)

    /** Desktop `ServerHandler::removeChannel`. */
    fun removeChannel(channelId: Int)

    /** Desktop `RequestBlob.channel_description` when the tree only has a hash. */
    fun requestChannelDescription(channelId: Int)

    /** Replaces the session access-token list (desktop `ServerHandler::setTokens`). */
    fun setTokens(tokens: List<String>)

    /** Requests [UserStats] for [session]. */
    fun requestUserStats(session: Int, statsOnly: Boolean = false)

    /** Requests the local user's permissions in [channelId]. */
    fun queryPermissions(channelId: Int)

    /** Desktop `ServerHandler::requestACL`: query=true. */
    fun requestAcl(channelId: Int)

    /** Desktop `ACLEditor::accept` ACL write (query unset). */
    fun sendAcl(msg: ACL)

    /** Desktop `ACLEditor::accept` from a structured snapshot. */
    fun sendAcl(snapshot: ChanAclSnapshot)

    /** Desktop `ACLEditor::id` / name refresh. */
    fun queryUsers(ids: List<Int> = emptyList(), names: List<String> = emptyList())

    /** Desktop `ServerHandler::setUserComment`. */
    fun setUserComment(session: Int, comment: String)

    /** Desktop `on_qaUserCommentReset_triggered`. */
    fun resetUserComment(session: Int)

    /** Desktop `ServerHandler::setUserTexture`. */
    fun setUserTexture(session: Int, texture: ByteArray)

    /** Desktop `on_qaUserTextureReset_triggered`. */
    fun resetUserTexture(session: Int)

    /** Desktop `ServerHandler::requestUserList`. */
    fun requestUserList()

    /** Desktop `UserEdit::accept`: only changed users. */
    fun sendUserList(users: List<RegisteredUser>)

    /** Desktop `ServerHandler::requestBanList`. */
    fun requestBanList()

    /** Desktop `BanEditor::accept`: full replacement list, query unset. */
    fun sendBanList(bans: List<BanEntry>)

    /** Sends a voice packet over the TCP control channel (force-TCP mode). */
    fun sendTunneledVoice(body: ByteArray)

    /** Requests a crypt-nonce resync (empty CryptSetup). */
    fun requestCryptResync()

    /** Reports our current encryption IV so the server can resync its decrypt IV. */
    fun sendCryptClientNonce(nonce: ByteArray)
}

/**
 * Assembles and writes the typed control messages (official `ServerHandler`).
 *
 * The framing, the serialized write path and the access-token list stay owned by
 * the [Host] ([MumbleClient]): this collaborator only turns a semantic call into
 * a `MessageType` + protobuf payload.
 */
internal class MumbleControlSenders : MumbleControlSender {

    /** The connection this sender writes to. Assigned right after construction. */
    internal interface Host {
        /** Frames and writes a protobuf message. */
        fun writeMessage(type: Int, message: MessageLite)

        /** Frames and writes a raw body. */
        fun writeBytes(type: Int, body: ByteArray)

        /** Session id of the local user (0 until ServerSync). */
        fun localSession(): Int

        /** Live access-token list, mutated by [setTokens]. */
        fun accessTokens(): MutableList<String>
    }

    private var host: Host? = null

    /**
     * Binds the connection. Called from the host's `init`, so it cannot take the
     * host as a constructor argument (a delegating supertype cannot reference
     * `this` before the instance is initialized).
     */
    fun attach(host: Host) {
        this.host = host
    }

    private fun h(): Host = requireNotNull(host) { "MumbleControlSenders not attached" }

    private fun writeMessage(type: Int, message: MessageLite) = h().writeMessage(type, message)

    private fun writeBytes(type: Int, body: ByteArray) = h().writeBytes(type, body)

    private fun localSession(): Int = h().localSession()

    private fun accessTokens(): MutableList<String> = h().accessTokens()


    /** Sends a text message to a channel. */
    override fun sendTextToChannel(channelId: Int, text: String) {
        val msg = TextMessage.newBuilder()
            .setMessage(text)
            .addChannelId(channelId)
            .build()
        writeMessage(MessageType.TEXT_MESSAGE, msg)
    }

    /** Sends a private (direct) text message to a specific user session. */
    override fun sendTextToUser(session: Int, text: String) {
        val msg = TextMessage.newBuilder()
            .setMessage(text)
            .addSession(session)
            .build()
        writeMessage(MessageType.TEXT_MESSAGE, msg)
    }

    /**
     * Desktop `ServerHandler::kickUser`: `UserRemove` with `ban = false`.
     */
    override fun kickUser(session: Int, reason: String) {
        writeMessage(
            MessageType.USER_REMOVE,
            UserModeration.kick(session, reason),
        )
    }

    /**
     * Desktop `ServerHandler::banUser`: `UserRemove` with `ban = true` and
     * the 1.6+ certificate/IP flags. Duration is not on this message;
     * Murmur always stores 0, so timed user-menu bans patch BanList after.
     */
    override fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
    ) {
        writeMessage(
            MessageType.USER_REMOVE,
            UserModeration.ban(session, reason, banCertificate, banIp),
        )
    }

    /**
     * Desktop `ServerHandler::registerUser`: `UserState` with `user_id = 0`.
     */
    override fun registerUser(session: Int) {
        writeMessage(MessageType.USER_STATE, UserModeration.register(session))
    }

    /**
     * Moves the local user to [channelId]. [temporaryAccessTokens] are official
     * channel passwords applied only for this UserState (murmur
     * `TemporaryAccessTokenHelper`).
     */
    override fun joinChannel(channelId: Int, temporaryAccessTokens: List<String>) {
        val us = UserState.newBuilder()
            .setSession(localSession())
            .setChannelId(channelId)
        temporaryAccessTokens.forEach { us.addTemporaryAccessTokens(it) }
        writeMessage(MessageType.USER_STATE, us.build())
    }

    /**
     * Desktop `ServerHandler::joinChannel` targeting another user's session.
     * The server requires Move on their current channel, and Move on the
     * destination or Enter for the target.
     */
    override fun moveUser(session: Int, channelId: Int) {
        writeMessage(MessageType.USER_STATE, UserModeration.moveToChannel(session, channelId))
    }

    /**
     * Desktop `ServerHandler::startListeningToChannel` /
     * `stopListeningToChannel`.
     */
    override fun setChannelListening(channelId: Int, listen: Boolean) {
        writeMessage(
            MessageType.USER_STATE,
            UserModeration.setChannelListening(localSession(), channelId, listen),
        )
    }

    /**
     * Desktop `ServerHandler::createChannel`: ChannelState without `channel_id`.
     */
    override fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
    ) {
        writeMessage(
            MessageType.CHANNEL_STATE,
            ChannelModeration.create(parentId, name, description, position, temporary, maxUsers),
        )
    }

    /**
     * Desktop `ACLEditor::accept` update path: ChannelState with only
     * changed fields. No-op when [msg] is null.
     */
    override fun updateChannel(msg: ChannelState?) {
        if (msg == null) return
        writeMessage(MessageType.CHANNEL_STATE, msg)
    }

    /** Desktop `ServerHandler::removeChannel`. */
    override fun removeChannel(channelId: Int) {
        writeMessage(MessageType.CHANNEL_REMOVE, ChannelModeration.remove(channelId))
    }

    /**
     * Desktop `RequestBlob.channel_description` when the tree only has a
     * description hash.
     */
    override fun requestChannelDescription(channelId: Int) {
        writeMessage(
            MessageType.REQUEST_BLOB,
            RequestBlob.newBuilder().addChannelDescription(channelId).build(),
        )
    }

    /**
     * Replaces the session access-token list (desktop `ServerHandler::setTokens`).
     * Sent as Authenticate with only `tokens` while already connected.
     */
    override fun setTokens(tokens: List<String>) {
        accessTokens().clear()
        accessTokens().addAll(tokens)
        val auth = Authenticate.newBuilder()
        tokens.forEach { auth.addTokens(it) }
        writeMessage(MessageType.AUTHENTICATE, auth.build())
    }

    /**
     * Requests [UserStats] for [session]. The first open of the desktop
     * Information dialog uses [statsOnly] = false so certificates/version/IP
     * are included when the server allows them; later refreshes pass true.
     */
    override fun requestUserStats(session: Int, statsOnly: Boolean) {
        val msg = UserStats.newBuilder()
            .setSession(session)
            .setStatsOnly(statsOnly)
            .build()
        writeMessage(MessageType.USER_STATS, msg)
    }

    /**
     * Requests the local user's permissions in [channelId] from the server.
     * The server replies with a PermissionQuery carrying the permission bit
     * flags, which the service uses to decide whether server-side mute/deafen
     * actions are allowed.
     */
    override fun queryPermissions(channelId: Int) {
        val pq = PermissionQuery.newBuilder()
            .setChannelId(channelId)
            .build()
        writeMessage(MessageType.PERMISSION_QUERY, pq)
    }

    /** Desktop `ServerHandler::requestACL`: query=true. */
    override fun requestAcl(channelId: Int) {
        writeMessage(MessageType.ACL, ChanAclWrite.query(channelId))
    }

    /** Desktop `ACLEditor::accept` ACL write (query unset). */
    override fun sendAcl(msg: ACL) {
        writeMessage(MessageType.ACL, msg)
    }

    /** Desktop `ACLEditor::accept` from a structured snapshot. */
    override fun sendAcl(snapshot: ChanAclSnapshot) {
        sendAcl(ChanAclWrite.toWriteMessage(snapshot))
    }

    /**
     * Desktop `ACLEditor::id` / name refresh: server fills the missing
     * id↔name side and replies with QueryUsers.
     */
    override fun queryUsers(ids: List<Int>, names: List<String>) {
        val builder = QueryUsers.newBuilder()
        ids.filter { it >= ChanACL.UserId.SUPERUSER }.forEach { builder.addIds(it) }
        names.forEach { builder.addNames(it) }
        writeMessage(MessageType.QUERY_USERS, builder.build())
    }

    /** Desktop `ServerHandler::setUserComment`. */
    override fun setUserComment(session: Int, comment: String) {
        writeMessage(MessageType.USER_STATE, UserModeration.setComment(session, comment))
    }

    /** Desktop `on_qaUserCommentReset_triggered`. */
    override fun resetUserComment(session: Int) {
        setUserComment(session, "")
    }

    /** Desktop `ServerHandler::setUserTexture`. */
    override fun setUserTexture(session: Int, texture: ByteArray) {
        writeMessage(MessageType.USER_STATE, UserModeration.setTexture(session, texture))
    }

    /** Desktop `on_qaUserTextureReset_triggered`. */
    override fun resetUserTexture(session: Int) {
        setUserTexture(session, ByteArray(0))
    }

    /** Desktop `ServerHandler::requestUserList`. */
    override fun requestUserList() {
        writeMessage(MessageType.USER_LIST, UserList.newBuilder().build())
    }

    /**
     * Desktop `UserEdit::accept`: only changed users. Omit `name` to
     * unregister (`clear_name()` / `!has_name()`); murmur treats an empty
     * name as a rename, not a delete.
     */
    override fun sendUserList(users: List<RegisteredUser>) {
        val msg = UserList.newBuilder()
        for (user in users) {
            val entry = UserList.User.newBuilder().setUserId(user.userId)
            if (user.name.isNotEmpty()) {
                entry.setName(user.name)
            }
            msg.addUsers(entry)
        }
        writeMessage(MessageType.USER_LIST, msg.build())
    }

    /** Desktop `ServerHandler::requestBanList`. */
    override fun requestBanList() {
        writeMessage(MessageType.BAN_LIST, BanList.newBuilder().setQuery(true).build())
    }

    /** Desktop `BanEditor::accept`: full replacement list, query unset. */
    override fun sendBanList(bans: List<BanEntry>) {
        val msg = BanList.newBuilder()
        for (ban in bans) {
            val entry = BanList.BanEntry.newBuilder()
                .setMask(ban.mask)
                .setName(ban.name)
                .setHash(ban.hash)
                .setReason(ban.reason)
                .setStart(ban.start)
                .setDuration(ban.duration)
            if (ban.address.isNotEmpty()) {
                entry.setAddress(ByteString.copyFrom(ban.address))
            }
            msg.addBans(entry)
        }
        writeMessage(MessageType.BAN_LIST, msg.build())
    }

    /**
     * Sends a voice packet over the TCP control channel (force-TCP mode). The
     * payload is the plaintext voice packet in the negotiated framing
     * (TCP is already TLS-encrypted, so voice is not OCB2-encrypted in tunnel
     * mode).
     *
     * @param body the plaintext UDPTunnel body.
     */
    override fun sendTunneledVoice(body: ByteArray) {
        writeBytes(MessageType.UDP_TUNNEL, body)
    }

    /**
     * Requests a crypt-nonce resync by sending an empty CryptSetup message
     * (mirrors the official client's behaviour when UDP decryption keeps
     * failing). The server replies with its current encrypt IV.
     */
    override fun requestCryptResync() {
        writeMessage(MessageType.CRYPT_SETUP, CryptSetup.newBuilder().build())
    }

    /**
     * Reports our current encryption IV to the server so it can resync its
     * decryption IV (mirrors the official client's reply to a CryptSetup that
     * only carries our client nonce).
     */
    override fun sendCryptClientNonce(nonce: ByteArray) {
        writeMessage(
            MessageType.CRYPT_SETUP,
            CryptSetup.newBuilder()
                .setClientNonce(com.google.protobuf.ByteString.copyFrom(nonce))
                .build(),
        )
    }

}
