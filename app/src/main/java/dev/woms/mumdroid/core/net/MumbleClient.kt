package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.model.ChanAclWrite
import dev.woms.mumdroid.core.model.ChannelModeration
import dev.woms.mumdroid.core.model.UserModeration
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.Authenticate
import dev.woms.mumdroid.core.proto.BanList
import dev.woms.mumdroid.core.proto.ChannelState
import dev.woms.mumdroid.core.proto.CryptSetup
import dev.woms.mumdroid.core.proto.PermissionQuery
import dev.woms.mumdroid.core.proto.Ping
import dev.woms.mumdroid.core.proto.QueryUsers
import dev.woms.mumdroid.core.proto.RequestBlob
import dev.woms.mumdroid.core.proto.TextMessage
import dev.woms.mumdroid.core.proto.UserList
import dev.woms.mumdroid.core.proto.UserState
import dev.woms.mumdroid.core.proto.UserStats
import dev.woms.mumdroid.core.proto.Version
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/**
 * The Mumble protocol client. Owns the TCP/TLS control connection: the
 * connect / teardown lifecycle, message framing with the serialized write
 * path, the TCP ping heartbeat, and the typed sender API (the official
 * `ServerHandler` surface).
 *
 * Focused collaborators own the individual policies:
 *  - [ClientTlsPolicy] — trust / certificate-pinning policy, the
 *    certificate-mismatch gate, and fingerprint / TLS-session capture,
 *  - [MumbleMessageRouter] — inbound message parsing and listener fan-out
 *    (this class implements its [MumbleMessageRouter.Host] to receive the
 *    stateful Version / ServerSync / Ping values).
 *
 * The client itself has no voice-channel crypt state: the OCB2 counters and
 * UDP RTT statistics reported in the TCP Ping come from [statsProvider].
 */
class MumbleClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val listener: MumbleListener,
    private val clientCert: X509Certificate? = null,
    private val clientKey: java.security.PrivateKey? = null,
    initialAccessTokens: List<String> = emptyList(),
    private val certificatePinning: Boolean = true,
    private val pinnedFingerprint: String? = null,
) : MumbleMessageRouter.Host {
    companion object {
        private const val TAG = "MumbleClient"
        private const val TIMEOUT_MS = 15000

        // Official desktop default (`iPingIntervalMsec`) is 5 seconds. A 15 s
        // interval plus a 15 s SO_TIMEOUT raced the keep-alive and dropped
        // otherwise-healthy connections.
        private const val PING_INTERVAL_SECONDS = 5L
        /** Official `iMaxInFlightTCPPings` default: drop the connection after this many unanswered pings. */
        private const val MAX_IN_FLIGHT_TCP_PINGS = 4
        /** Official TCP frame cap (`Connection.cpp`: `iPacketLength > 0x7fffff`).
         *  USER_STATE with a large avatar texture or comment can exceed 1 MB. */
        private const val MAX_TCP_MESSAGE_BYTES = 0x7fffff

        // Our reported client version.
        //
        // IMPORTANT: Mumble 1.5.0 introduced the *protobuf* UDP voice protocol
        // (PROTOBUF_INTRODUCTION_VERSION = 1.5.0). A server decides which UDP
        // framing to use for a client based on the version that client reports:
        //   - version >= 1.5.0  -> protobuf-framed UDP packets
        //   - version <  1.5.0  -> legacy UDP framing (header byte + varints)
        //
        // Both framings are fully implemented (see UdpVoiceManager /
        // ProtoUdpCodec / UdpPacketCodec), so we advertise 1.5.0 and select the
        // framing based on the version the server reports in its Version
        // message (mirroring ServerHandler::setProtocolVersion of the official
        // client). Older servers keep speaking the legacy framing regardless.
        private const val CLIENT_VERSION = 0x010500 // 1.5.0
        private const val CLIENT_VERSION_V2 = 0x0001000500000000L // 1.5.0
        private const val CLIENT_OS = "Android"

        /** `Version.release`: app identity, not the protocol number. */
        internal fun clientRelease(
            versionName: String = BuildConfig.VERSION_NAME,
            gitHash: String = BuildConfig.GIT_HASH,
        ): String = "mumdroid $versionName-$gitHash"
    }

    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val accessTokens = initialAccessTokens.toMutableList()

    private val tls = ClientTlsPolicy(
        certificatePinning = certificatePinning,
        pinnedFingerprint = pinnedFingerprint,
        clientCert = clientCert,
        clientKey = clientKey,
        onCertificateError = { fingerprint, pinned, respond ->
            listener.onCertificateError(fingerprint, pinned, respond)
        },
    )

    private val router = MumbleMessageRouter(
        listener = listener,
        host = this,
        onIgnored = { type, bodySize ->
            Log.d(TAG, "Ignoring message type $type ($bodySize bytes)")
        },
    )

    private var pingExecutor: ScheduledExecutorService? = null
    /** Serializes TCP writes so UI-thread callers never touch the SSL socket. */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mumble-tcp-write").apply { isDaemon = true }
    }
    private val inFlightTcpPings = AtomicInteger(0)

    /**
     * Supplies live voice/stats information (crypt packet counters and the
     * measured UDP/TCP round-trip times) that are reported to the server in the
     * periodic TCP Ping message. The PC admin's "User Information" panel shows
     * these, so without them it would see no ping statistics for this client.
     * Implemented by [dev.woms.mumdroid.service.MumbleService] from its
     * [dev.woms.mumdroid.core.net.UdpVoiceManager].
     */
    fun interface StatsProvider {
        fun stats(): ConnectionStats
    }

    /** Aggregate connection statistics reported in the TCP Ping message. */
    data class ConnectionStats(
        val good: Int = 0,
        val late: Int = 0,
        val lost: Int = 0,
        val resync: Int = 0,
        val udpPingAvg: Float = 0f,
        val udpPingVar: Float = 0f,
        val udpPingPackets: Int = 0,
        val tcpPingAvg: Float = 0f,
        val tcpPingVar: Float = 0f,
        val tcpPingPackets: Int = 0,
    )

    @Volatile
    var statsProvider: StatsProvider? = null

    /** Called when the server replies to a TCP Ping; [rttMillis] is the measured
     *  round-trip time. Used by the service to report the TCP ping average. */
    fun interface TcpPingListener {
        fun onTcpPingReply(rttMillis: Long)
    }

    @Volatile
    var tcpPingListener: TcpPingListener? = null

    /**
     * Called on every TCP Ping reply with the server-reported crypt counters
     * ([remoteGood] good packets received/decrypted from us). Mirrors the
     * official client's `csCrypt->m_statsRemote`, used for UDP→TCP fallback.
     */
    @Volatile
    var pingStatsListener: ((remoteGood: Int, remoteLost: Int) -> Unit)? = null

    private var localSession = 0

    /** The session id of the local user, once connected. */
    val currentSession: Int
        get() = localSession

    /** SHA-256 fingerprint of the server certificate, for pinning. */
    val serverFingerprint: String?
        get() = tls.serverFingerprint

    /**
     * Local address of the TCP/TLS socket, used to bind the UDP voice socket
     * to the same interface (official `bUdpForceTcpAddr`).
     */
    @Volatile
    var localAddress: java.net.InetAddress? = null
        private set

    /**
     * Peer address of the TCP/TLS socket. UDP voice must be sent here (official
     * `qhaRemote = connection->peerAddress()`), not re-resolved from the
     * hostname — a second DNS lookup can yield a different A/AAAA record.
     */
    @Volatile
    var remoteAddress: java.net.InetAddress? = null
        private set

    /**
     * The protocol version the server reported in its Version message
     * (v2 format; 0 until received). Determines the negotiated UDP framing.
     */
    @Volatile
    var serverVersionV2: Long = 0
        private set

    @Volatile
    var serverVersionLegacy: Int = 0
        private set

    @Volatile
    var serverRelease: String = ""
        private set

    @Volatile
    var serverOs: String = ""
        private set

    @Volatile
    var serverOsVersion: String = ""
        private set

    /** TLS session info (see [ClientTlsPolicy]). */
    val tlsProtocol: String
        get() = tls.tlsProtocol

    /** TLS session info (see [ClientTlsPolicy]). */
    val tlsCipherSuite: String
        get() = tls.tlsCipherSuite

    /** Server-reported crypt counters (desktop `csCrypt->m_statsRemote`). */
    @Volatile
    var remoteCryptGood: Int = 0
        private set

    @Volatile
    var remoteCryptLate: Int = 0
        private set

    @Volatile
    var remoteCryptLost: Int = 0
        private set

    @Volatile
    var remoteCryptResync: Int = 0
        private set

    /** Called from a background thread; must not block the main thread. */
    fun connect() {
        if (running.getAndSet(true)) return
        // Drop leftovers if this instance is reused after close(); a new
        // handshake must not advertise the previous peer's TLS / version.
        clearSessionIdentity()
        try {
            val ssl = tls.createSslContext()
            val factory = ssl.socketFactory
            val rawSocket = factory.createSocket() as SSLSocket
            rawSocket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            // Handshake may block; keep a timeout until we are authenticated,
            // then clear it so a quiet control channel cannot drop the socket.
            rawSocket.soTimeout = TIMEOUT_MS
            rawSocket.tcpNoDelay = true

            // Run the TLS handshake explicitly instead of lazily on first I/O:
            // a certificate problem (including a rejected pin) must surface
            // here with its cause intact, and the fingerprint capture below
            // needs a completed session.
            rawSocket.startHandshake()

            tls.captureSession(rawSocket)
            socket = rawSocket
            localAddress = rawSocket.localAddress
            remoteAddress = rawSocket.inetAddress
            input = DataInputStream(rawSocket.inputStream)
            output = DataOutputStream(rawSocket.outputStream)

            sendVersion()
            sendAuthenticate()

            connected.set(true)
            rawSocket.soTimeout = 0
            inFlightTcpPings.set(0)

            startPingLoop()

            readLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            // A client that was explicitly close()d — e.g. a disconnect while
            // the TLS handshake was still in flight — must stay silent: its
            // late failure callback would otherwise clobber the state of a
            // newer session (readLoop applies the same guard).
            disconnect(failureMessage(e))
        } finally {
            close()
        }
    }

    /** A readable failure reason, unwrapping a user-rejected server certificate. */
    private fun failureMessage(e: Exception): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CertificateRejected) {
                return ClientTlsPolicy.CERTIFICATE_REJECTED
            }
            cause = cause.cause
        }
        return e.message ?: "Connection failed"
    }

    /** Sends a typed control message with the 6-byte big-endian header.
     *  Must not lock on `this`: a UI caller would otherwise block on an
     *  in-flight [writeLocked] SSL write before [sendBytes] can hop off
     *  the main thread. Socket writes are serialized in [writeLocked]. */
    fun sendMessage(type: Int, message: MessageLite) {
        sendBytes(type, message.toByteArray())
    }

    /** Sends a raw payload with the 6-byte big-endian header. */
    fun sendBytes(type: Int, body: ByteArray) {
        if (!running.get()) return
        val main = android.os.Looper.getMainLooper()
        if (main != null && Thread.currentThread() === main.thread) {
            val copy = body.copyOf()
            writeExecutor.execute { writeLocked(type, copy) }
            return
        }
        writeLocked(type, body)
    }

    @Synchronized
    private fun writeLocked(type: Int, body: ByteArray) {
        val out = output ?: return
        if (body.size > MAX_TCP_MESSAGE_BYTES) {
            Log.w(TAG, "TCP write dropped: message size ${body.size}")
            return
        }
        try {
            out.writeShort(type)
            out.writeInt(body.size)
            out.write(body)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "TCP write failed", e)
        }
    }

    private fun readLoop() {
        val input = input ?: return
        while (running.get() && connected.get()) {
            try {
                val type = input.readUnsignedShort()
                val size = input.readInt()
                if (size < 0 || size > MAX_TCP_MESSAGE_BYTES) {
                    disconnect("Invalid message size")
                    return
                }
                val body = ByteArray(size)
                input.readFully(body)
                router.dispatch(type, body)
            } catch (e: Exception) {
                disconnect(e.message ?: "Read error")
                return
            }
        }
    }

    private fun startPingLoop() {
        pingExecutor = Executors.newSingleThreadScheduledExecutor()
        pingExecutor?.scheduleWithFixedDelay(
            {
                try {
                    if (inFlightTcpPings.get() >= MAX_IN_FLIGHT_TCP_PINGS) {
                        disconnect("Server is not responding to TCP pings")
                        return@scheduleWithFixedDelay
                    }
                    sendMessage(MessageType.PING, buildPingWithStats(SystemClock.elapsedRealtime()))
                    inFlightTcpPings.incrementAndGet()
                } catch (e: Exception) {
                    Log.e(TAG, "Ping failed", e)
                }
            },
            PING_INTERVAL_SECONDS,
            PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Builds a TCP Ping message carrying the given [timestamp] together with
     * the client's voice statistics (crypt packet counters and UDP/TCP RTT).
     * The counters come exclusively from [statsProvider] — the client itself
     * has no crypt state, so it cannot count OCB2 packets. They are consumed
     * by the server and surfaced in the PC admin's user info.
     */
    private fun buildPingWithStats(timestamp: Long): Ping {
        val builder = Ping.newBuilder()
            .setTimestamp(timestamp)

        // Live voice statistics (crypt counters + UDP/TCP RTT) from the service.
        statsProvider?.stats()?.let { s ->
            builder.setGood(s.good)
                .setLate(s.late)
                .setLost(s.lost)
                .setResync(s.resync)
            if (s.udpPingPackets > 0) {
                builder.setUdpPackets(s.udpPingPackets)
                builder.setUdpPingAvg(s.udpPingAvg)
                builder.setUdpPingVar(s.udpPingVar)
            }
            if (s.tcpPingPackets > 0) {
                builder.setTcpPackets(s.tcpPingPackets)
                builder.setTcpPingAvg(s.tcpPingAvg)
                builder.setTcpPingVar(s.tcpPingVar)
            }
        }
        return builder.build()
    }

    // ---- MumbleMessageRouter.Host: stateful message side-effects ----

    override fun onServerVersionMessage(
        versionV2: Long,
        versionLegacy: Int,
        release: String,
        os: String,
        osVersion: String,
    ) {
        serverVersionV2 = versionV2
        serverVersionLegacy = versionLegacy
        serverRelease = release
        serverOs = os
        serverOsVersion = osVersion
    }

    override fun onServerSync(session: Int) {
        localSession = session
    }

    override fun onPing(timestampMs: Long, good: Int, late: Int, lost: Int, resync: Int) {
        inFlightTcpPings.set(0)
        // Official `tTimestamp` is a QElapsedTimer (monotonic). Wall
        // time would let NTP steps land inside the 60 s window and
        // pollute tcpPingAvg / tcpPingVar.
        val now = SystemClock.elapsedRealtime()
        if (timestampMs in 1 until now) {
            val rtt = now - timestampMs
            if (rtt < 60_000) {
                tcpPingListener?.onTcpPingReply(rtt)
            }
        }
        // Surface the server-reported crypt statistics (its view of the
        // UDP packets we sent). The official client uses exactly these
        // counters to decide whether to fall back to TCP mode.
        remoteCryptGood = good
        remoteCryptLate = late
        remoteCryptLost = lost
        remoteCryptResync = resync
        pingStatsListener?.invoke(good, lost)
    }

    // ---- Handshake ----

    private fun sendVersion() {
        val version = Version.newBuilder()
            .setVersionV1(CLIENT_VERSION)
            .setRelease(clientRelease())
            .setOs(CLIENT_OS)
            .setOsVersion(android.os.Build.VERSION.RELEASE ?: "unknown")
            .setVersionV2(CLIENT_VERSION_V2)
            .build()
        sendMessage(MessageType.VERSION, version)
    }

    private fun sendAuthenticate() {
        val auth = Authenticate.newBuilder()
            .setUsername(username)
            .setPassword(password)
            .setOpus(true)
            .setClientType(0)
        accessTokens.forEach { auth.addTokens(it) }
        sendMessage(MessageType.AUTHENTICATE, auth.build())
    }

    // ---- Typed senders (official ServerHandler surface) ----

    /** Sends a text message to a channel. */
    fun sendTextToChannel(channelId: Int, text: String) {
        val msg = TextMessage.newBuilder()
            .setMessage(text)
            .addChannelId(channelId)
            .build()
        sendMessage(MessageType.TEXT_MESSAGE, msg)
    }

    /** Sends a private (direct) text message to a specific user session. */
    fun sendTextToUser(session: Int, text: String) {
        val msg = TextMessage.newBuilder()
            .setMessage(text)
            .addSession(session)
            .build()
        sendMessage(MessageType.TEXT_MESSAGE, msg)
    }

    /**
     * Desktop `ServerHandler::kickUser`: `UserRemove` with `ban = false`.
     */
    fun kickUser(session: Int, reason: String) {
        sendMessage(
            MessageType.USER_REMOVE,
            UserModeration.kick(session, reason),
        )
    }

    /**
     * Desktop `ServerHandler::banUser`: `UserRemove` with `ban = true` and
     * the 1.6+ certificate/IP flags. Duration is not on this message;
     * Murmur always stores 0, so timed user-menu bans patch BanList after.
     */
    fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
    ) {
        sendMessage(
            MessageType.USER_REMOVE,
            UserModeration.ban(session, reason, banCertificate, banIp),
        )
    }

    /**
     * Desktop `ServerHandler::registerUser`: `UserState` with `user_id = 0`.
     */
    fun registerUser(session: Int) {
        sendMessage(MessageType.USER_STATE, UserModeration.register(session))
    }

    /**
     * Moves the local user to [channelId]. [temporaryAccessTokens] are official
     * channel passwords applied only for this UserState (murmur
     * `TemporaryAccessTokenHelper`).
     */
    fun joinChannel(channelId: Int, temporaryAccessTokens: List<String> = emptyList()) {
        val us = UserState.newBuilder()
            .setSession(localSession)
            .setChannelId(channelId)
        temporaryAccessTokens.forEach { us.addTemporaryAccessTokens(it) }
        sendMessage(MessageType.USER_STATE, us.build())
    }

    /**
     * Desktop `ServerHandler::joinChannel` targeting another user's session.
     * The server requires Move on their current channel, and Move on the
     * destination or Enter for the target.
     */
    fun moveUser(session: Int, channelId: Int) {
        sendMessage(MessageType.USER_STATE, UserModeration.moveToChannel(session, channelId))
    }

    /**
     * Desktop `ServerHandler::startListeningToChannel` /
     * `stopListeningToChannel`.
     */
    fun setChannelListening(channelId: Int, listen: Boolean) {
        sendMessage(
            MessageType.USER_STATE,
            UserModeration.setChannelListening(localSession, channelId, listen),
        )
    }

    /**
     * Desktop `ServerHandler::createChannel`: ChannelState without `channel_id`.
     */
    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
    ) {
        sendMessage(
            MessageType.CHANNEL_STATE,
            ChannelModeration.create(parentId, name, description, position, temporary, maxUsers),
        )
    }

    /**
     * Desktop `ACLEditor::accept` update path: ChannelState with only
     * changed fields. No-op when [msg] is null.
     */
    fun updateChannel(msg: ChannelState?) {
        if (msg == null) return
        sendMessage(MessageType.CHANNEL_STATE, msg)
    }

    /** Desktop `ServerHandler::removeChannel`. */
    fun removeChannel(channelId: Int) {
        sendMessage(MessageType.CHANNEL_REMOVE, ChannelModeration.remove(channelId))
    }

    /**
     * Desktop `RequestBlob.channel_description` when the tree only has a
     * description hash.
     */
    fun requestChannelDescription(channelId: Int) {
        sendMessage(
            MessageType.REQUEST_BLOB,
            RequestBlob.newBuilder().addChannelDescription(channelId).build(),
        )
    }

    /**
     * Replaces the session access-token list (desktop `ServerHandler::setTokens`).
     * Sent as Authenticate with only `tokens` while already connected.
     */
    fun setTokens(tokens: List<String>) {
        accessTokens.clear()
        accessTokens.addAll(tokens)
        val auth = Authenticate.newBuilder()
        tokens.forEach { auth.addTokens(it) }
        sendMessage(MessageType.AUTHENTICATE, auth.build())
    }

    /**
     * Requests [UserStats] for [session]. The first open of the desktop
     * Information dialog uses [statsOnly] = false so certificates/version/IP
     * are included when the server allows them; later refreshes pass true.
     */
    fun requestUserStats(session: Int, statsOnly: Boolean = false) {
        val msg = UserStats.newBuilder()
            .setSession(session)
            .setStatsOnly(statsOnly)
            .build()
        sendMessage(MessageType.USER_STATS, msg)
    }

    /**
     * Requests the local user's permissions in [channelId] from the server.
     * The server replies with a PermissionQuery carrying the permission bit
     * flags, which the service uses to decide whether server-side mute/deafen
     * actions are allowed.
     */
    fun queryPermissions(channelId: Int) {
        val pq = PermissionQuery.newBuilder()
            .setChannelId(channelId)
            .build()
        sendMessage(MessageType.PERMISSION_QUERY, pq)
    }

    /** Desktop `ServerHandler::requestACL`: query=true. */
    fun requestAcl(channelId: Int) {
        sendMessage(MessageType.ACL, ChanAclWrite.query(channelId))
    }

    /** Desktop `ACLEditor::accept` ACL write (query unset). */
    fun sendAcl(msg: ACL) {
        sendMessage(MessageType.ACL, msg)
    }

    /** Desktop `ACLEditor::accept` from a structured snapshot. */
    fun sendAcl(snapshot: ChanAclSnapshot) {
        sendAcl(ChanAclWrite.toWriteMessage(snapshot))
    }

    /**
     * Desktop `ACLEditor::id` / name refresh: server fills the missing
     * id↔name side and replies with QueryUsers.
     */
    fun queryUsers(ids: List<Int> = emptyList(), names: List<String> = emptyList()) {
        val builder = QueryUsers.newBuilder()
        ids.filter { it >= ChanACL.UserId.SUPERUSER }.forEach { builder.addIds(it) }
        names.forEach { builder.addNames(it) }
        sendMessage(MessageType.QUERY_USERS, builder.build())
    }

    /** Desktop `ServerHandler::setUserComment`. */
    fun setUserComment(session: Int, comment: String) {
        sendMessage(MessageType.USER_STATE, UserModeration.setComment(session, comment))
    }

    /** Desktop `on_qaUserCommentReset_triggered`. */
    fun resetUserComment(session: Int) {
        setUserComment(session, "")
    }

    /** Desktop `ServerHandler::setUserTexture`. */
    fun setUserTexture(session: Int, texture: ByteArray) {
        sendMessage(MessageType.USER_STATE, UserModeration.setTexture(session, texture))
    }

    /** Desktop `on_qaUserTextureReset_triggered`. */
    fun resetUserTexture(session: Int) {
        setUserTexture(session, ByteArray(0))
    }

    /** Desktop `ServerHandler::requestUserList`. */
    fun requestUserList() {
        sendMessage(MessageType.USER_LIST, UserList.newBuilder().build())
    }

    /**
     * Desktop `UserEdit::accept`: only changed users. Omit `name` to
     * unregister (`clear_name()` / `!has_name()`); murmur treats an empty
     * name as a rename, not a delete.
     */
    fun sendUserList(users: List<RegisteredUser>) {
        val msg = UserList.newBuilder()
        for (user in users) {
            val entry = UserList.User.newBuilder().setUserId(user.userId)
            if (user.name.isNotEmpty()) {
                entry.setName(user.name)
            }
            msg.addUsers(entry)
        }
        sendMessage(MessageType.USER_LIST, msg.build())
    }

    /** Desktop `ServerHandler::requestBanList`. */
    fun requestBanList() {
        sendMessage(MessageType.BAN_LIST, BanList.newBuilder().setQuery(true).build())
    }

    /** Desktop `BanEditor::accept`: full replacement list, query unset. */
    fun sendBanList(bans: List<BanEntry>) {
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
        sendMessage(MessageType.BAN_LIST, msg.build())
    }

    /**
     * Sends a voice packet over the TCP control channel (force-TCP mode). The
     * payload is the plaintext voice packet in the negotiated framing
     * (TCP is already TLS-encrypted, so voice is not OCB2-encrypted in tunnel
     * mode).
     *
     * @param body the plaintext UDPTunnel body.
     */
    fun sendTunneledVoice(body: ByteArray) {
        sendBytes(MessageType.UDP_TUNNEL, body)
    }

    /**
     * Requests a crypt-nonce resync by sending an empty CryptSetup message
     * (mirrors the official client's behaviour when UDP decryption keeps
     * failing). The server replies with its current encrypt IV.
     */
    fun requestCryptResync() {
        sendMessage(MessageType.CRYPT_SETUP, CryptSetup.newBuilder().build())
    }

    /**
     * Reports our current encryption IV to the server so it can resync its
     * decryption IV (mirrors the official client's reply to a CryptSetup that
     * only carries our client nonce).
     */
    fun sendCryptClientNonce(nonce: ByteArray) {
        sendMessage(
            MessageType.CRYPT_SETUP,
            CryptSetup.newBuilder()
                .setClientNonce(com.google.protobuf.ByteString.copyFrom(nonce))
                .build(),
        )
    }

    /**
     * Single teardown path for every unexpected disconnection (ping timeout,
     * read error, malformed frame, handshake failure). The CAS on [running]
     * is the onDisconnected-once guarantee: whichever thread flips the flag
     * first delivers the callback and closes the socket, every later arrival
     * — e.g. the read loop waking up on the closed socket, or a lingering
     * ping-timer tick before `close()` — silently returns instead of firing
     * a second `onDisconnected`. Explicit `close()` sets [running] via plain
     * `set`, so an external disconnect either wins the race (no callback,
     * user-initiated) or loses it (this callback wins); never both.
     */
    private fun disconnect(reason: String) {
        if (!running.getAndSet(false)) return
        connected.set(false)
        listener.onDisconnected(reason)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Clears peer identity captured from TLS / Version / Ping. Official
     * `ServerHandler` is per-connection; these fields are the closest we
     * have, so they must not outlive the socket. The TLS-side fields are
     * cleared by [ClientTlsPolicy.reset]; the active pin survives.
     */
    private fun clearSessionIdentity() {
        tls.reset()
        serverVersionV2 = 0
        serverVersionLegacy = 0
        serverRelease = ""
        serverOs = ""
        serverOsVersion = ""
        remoteCryptGood = 0
        remoteCryptLate = 0
        remoteCryptLost = 0
        remoteCryptResync = 0
    }

    fun close() {
        running.set(false)
        connected.set(false)
        inFlightTcpPings.set(0)
        // Release a pending certificate prompt so the handshake thread cannot
        // block forever on a dialog nobody will answer any more.
        tls.abort()
        pingExecutor?.shutdownNow()
        pingExecutor = null
        writeExecutor.shutdownNow()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        localAddress = null
        remoteAddress = null
        input = null
        output = null
        clearSessionIdentity()
    }
}
