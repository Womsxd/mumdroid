package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.ChannelRemove
import dev.woms.mumdroid.core.proto.ChannelState
import dev.woms.mumdroid.core.proto.CodecVersion
import dev.woms.mumdroid.core.proto.ContextAction
import dev.woms.mumdroid.core.proto.ContextActionModify
import dev.woms.mumdroid.core.proto.CryptSetup
import dev.woms.mumdroid.core.proto.PermissionDenied
import dev.woms.mumdroid.core.proto.PermissionQuery
import dev.woms.mumdroid.core.proto.Ping
import dev.woms.mumdroid.core.proto.QueryUsers
import dev.woms.mumdroid.core.proto.Reject
import dev.woms.mumdroid.core.proto.RequestBlob
import dev.woms.mumdroid.core.proto.ServerConfig
import dev.woms.mumdroid.core.proto.ServerSync
import dev.woms.mumdroid.core.proto.SuggestConfig
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.UserList
import dev.woms.mumdroid.core.proto.UserRemove
import dev.woms.mumdroid.core.proto.UserState
import dev.woms.mumdroid.core.proto.UserStats
import dev.woms.mumdroid.core.proto.Version
import dev.woms.mumdroid.core.proto.VoiceTarget

/**
 * Inbound control-message routing (official `ServerHandler::message` dispatch):
 * parses each framed message body and fans out to the [MumbleListener].
 *
 * Three messages carry connection state, so their parsed values are reported
 * to the [Host] (the client updates its own `@Volatile` fields, the in-flight
 * ping counter and the ping-RTT sanity checks there) — everything else is a
 * pure parse-and-forward.
 *
 * No Android logging here: unhandled / out-of-scope messages are reported
 * through [onIgnored] so the router stays testable on the JVM.
 */
internal class MumbleMessageRouter(
    private val listener: MumbleListener,
    private val host: Host,
    private val onIgnored: (type: Int, bodySize: Int) -> Unit = { _, _ -> },
) {
    /** State side-effects of the three stateful messages. */
    internal interface Host {
        /** The server's Version message decides the negotiated UDP framing. */
        fun onServerVersionMessage(
            versionV2: Long,
            versionLegacy: Int,
            release: String,
            os: String,
            osVersion: String,
        )

        /** The authenticated session id from ServerSync. */
        fun onServerSync(session: Int)

        /**
         * The server's Ping echo: [timestampMs] is our own echoed timestamp;
         * the counters are the server's view of our UDP crypt stream.
         */
        fun onPing(timestampMs: Long, good: Int, late: Int, lost: Int, resync: Int)
    }

    fun dispatch(type: Int, body: ByteArray) {
        when (type) {
            MessageType.VERSION -> {
                val v = Version.parseFrom(body)
                host.onServerVersionMessage(
                    versionV2 = v.versionV2,
                    versionLegacy = v.versionV1,
                    release = if (v.hasRelease()) v.release else "",
                    os = if (v.hasOs()) v.os else "",
                    osVersion = if (v.hasOsVersion()) v.osVersion else "",
                )
                listener.onServerVersion(v.versionV2, v.versionV1)
            }
            MessageType.SERVER_SYNC -> {
                val sync = ServerSync.parseFrom(body)
                host.onServerSync(sync.session)
                if (sync.permissions != 0L) {
                    // Official `static_cast<unsigned int>(msg.permissions())`.
                    listener.onPermissionQuery(0, ChanACL.fromWire(sync.permissions), false)
                }
                listener.onConnected(sync.session, sync.welcomeText, sync.maxBandwidth)
            }
            MessageType.REJECT -> {
                val reject = Reject.parseFrom(body)
                listener.onRejected(reject.reason, reject.type.number)
            }
            MessageType.CHANNEL_STATE -> {
                val cs = ChannelState.parseFrom(body)
                listener.onChannelStateProto(cs)
            }
            MessageType.CHANNEL_REMOVE -> {
                val cr = ChannelRemove.parseFrom(body)
                listener.onChannelRemoved(cr.channelId)
            }
            MessageType.USER_STATE -> {
                val us = UserState.parseFrom(body)
                listener.onUserState(us)
            }
            MessageType.USER_REMOVE -> {
                val ur = UserRemove.parseFrom(body)
                listener.onUserRemoved(
                    session = ur.session,
                    actor = ur.actor,
                    hasActor = ur.hasActor(),
                    reason = ur.reason,
                    ban = ur.ban,
                )
            }
            MessageType.TEXT_MESSAGE -> {
                val tm = TextMessage.parseFrom(body)
                // A message addressed to one or more explicit sessions (and not
                // broadcast to a channel) is a private/direct message.
                val isPrivate = tm.sessionList.isNotEmpty()
                listener.onTextMessage(
                    tm.actor.toString(),
                    tm.message,
                    tm.channelIdList.firstOrNull() ?: 0,
                    isPrivate,
                )
            }
            MessageType.PERMISSION_DENIED -> {
                val pd = PermissionDenied.parseFrom(body)
                listener.onPermissionDenied(pd)
            }
            MessageType.SERVER_CONFIG -> {
                val sc = ServerConfig.parseFrom(body)
                listener.onServerConfig(
                    if (sc.hasWelcomeText()) sc.welcomeText else "",
                    if (sc.hasMaxBandwidth()) sc.maxBandwidth else 0,
                    if (sc.hasMaxUsers()) sc.maxUsers else 0,
                )
            }
            MessageType.CRYPT_SETUP -> {
                // UDP encryption key setup.
                val cs = CryptSetup.parseFrom(body)
                listener.onCryptSetup(cs.key.toByteArray(), cs.clientNonce.toByteArray(), cs.serverNonce.toByteArray())
            }
            MessageType.BAN_LIST -> {
                // Server reports the current ban list (usually in response to a query).
                val bl = BanList.parseFrom(body)
                listener.onBanList(bl.bansList.map { b ->
                    BanEntry(
                        address = b.address.toByteArray(),
                        mask = b.mask,
                        name = b.name,
                        hash = b.hash,
                        reason = b.reason,
                        start = b.start,
                        duration = b.duration,
                    )
                }, bl.query)
            }
            MessageType.ACL -> {
                listener.onAcl(ACL.parseFrom(body))
            }
            MessageType.QUERY_USERS -> {
                // Server reply mapping user ids to names (and vice versa).
                val q = QueryUsers.parseFrom(body)
                listener.onQueryUsers(q.idsList, q.namesList)
            }
            MessageType.CONTEXT_ACTION_MODIFY -> {
                // Server registers/removes a context-menu action.
                val cam = ContextActionModify.parseFrom(body)
                listener.onContextActionModify(cam.action, cam.text, cam.context, cam.operation.number)
            }
            MessageType.CONTEXT_ACTION -> {
                // User invoked a context-menu action (session/channel scoped).
                val ca = ContextAction.parseFrom(body)
                listener.onContextAction(ca.session, ca.channelId, ca.action)
            }
            MessageType.USER_LIST -> {
                // Registered user list (user id -> name/last-seen).
                val ul = UserList.parseFrom(body)
                listener.onUserList(ul.usersList.map { u ->
                    RegisteredUser(u.userId, u.name, u.lastSeen, u.lastChannel)
                })
            }
            MessageType.VOICE_TARGET -> {
                // Server acknowledges a voice-target change (rarely used server->client).
                val vt = VoiceTarget.parseFrom(body)
                listener.onVoiceTarget(vt.id)
            }
            MessageType.PERMISSION_QUERY -> {
                // Server reports a user's permissions in a channel.
                val pq = PermissionQuery.parseFrom(body)
                listener.onPermissionQuery(
                    pq.channelId,
                    ChanACL.fromProtoUInt32(pq.permissions),
                    pq.flush,
                )
            }
            MessageType.USER_STATS -> {
                listener.onUserStats(UserStats.parseFrom(body))
            }
            MessageType.REQUEST_BLOB -> {
                val rb = RequestBlob.parseFrom(body)
                listener.onRequestBlob(rb.sessionTextureList, rb.sessionCommentList, rb.channelDescriptionList)
            }
            MessageType.SUGGEST_CONFIG -> {
                // Server suggests client configuration.
                val sc = SuggestConfig.parseFrom(body)
                listener.onSuggestConfig(sc.positional, sc.pushToTalk)
            }
            MessageType.CODEC_VERSION -> {
                val cv = CodecVersion.parseFrom(body)
                listener.onCodecVersion(cv.opus)
            }
            MessageType.UDP_TUNNEL -> {
                // Voice tunneled over the TCP control channel (force-TCP mode).
                // The body is the plaintext voice packet in the negotiated
                // framing (TCP is already TLS-encrypted); decoding is done by
                // the owner of the voice channel state.
                if (body.isNotEmpty()) {
                    listener.onTunneledPacket(body)
                }
            }
            MessageType.PING -> {
                // The server *echoes* our periodic Ping (see murmur
                // `Server::msgPing`). Official clients never send another Ping
                // in response — doing so forms an infinite ping-pong that
                // saturates the control channel and makes the reported TCP RTT
                // grow without bound (`now - originalTimestamp`). The echoed
                // timestamp is handed to the host untouched: the RTT clock and
                // its sanity window live there.
                val ping = Ping.parseFrom(body)
                host.onPing(ping.timestamp, ping.good, ping.late, ping.lost, ping.resync)
            }
            MessageType.PLUGIN_DATA_TRANSMISSION -> onIgnored(type, body.size)
            else -> onIgnored(type, body.size)
        }
    }
}
