package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.core.model.ChannelModeration
import dev.woms.mumdroid.core.model.ChannelPasswordAcl
import dev.woms.mumdroid.core.model.UserModeration
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.Authenticate
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The Mumble protocol client. Owns the TCP/TLS control connection, performs the
 * protocol handshake and dispatches incoming messages to a [MumbleListener].
 *
 * TLS handling follows the `certificatePinning` option:
 *
 *  - Pinning disabled: a trust-all trust manager is used, because Mumble
 *    servers commonly use self-signed certificates. The server certificate
 *    fingerprint is still captured and exposed so the UI can offer pinning /
 *    verification.
 *  - Pinning enabled: the presented certificate fingerprint must match the
 *    [pinnedFingerprint] captured on the first connection (the first
 *    connection itself is accepted silently and pinned by the caller). On a
 *    mismatch the handshake is paused and [MumbleListener.onCertificateError]
 *    asks the user to update the pin, trust the certificate once, or reject
 *    the connection.
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
) {
    companion object {
        private const val TAG = "MumbleClient"
        private const val TIMEOUT_MS = 15000

        /**
         * Disconnect reason reported when the user (or a closed session)
         * rejects the server certificate during the pinning check. The
         * service maps it to a localized string.
         */
        const val CERTIFICATE_REJECTED = "Server certificate rejected"
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

    /**
     * The fingerprint currently pinned for this server. Starts as the pinned
     * fingerprint captured on a previous connection and is replaced when the
     * user chooses to update the pin for the rest of the session.
     */
    @Volatile
    private var activePinnedFingerprint: String? = pinnedFingerprint

    /** Pauses the TLS handshake thread while the user reviews a certificate problem. */
    private val certificateGate = CertificateGate()

    private var pingExecutor: ScheduledExecutorService? = null
    /** Serializes TCP writes so UI-thread callers never touch the SSL socket. */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mumble-tcp-write").apply { isDaemon = true }
    }
    private val inFlightTcpPings = java.util.concurrent.atomic.AtomicInteger(0)

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
    var serverFingerprint: String? = null
        private set

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

    @Volatile
    var tlsProtocol: String = ""
        private set

    @Volatile
    var tlsCipherSuite: String = ""
        private set

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
            val ssl = createSslContext()
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

            captureFingerprint(rawSocket)
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
                return CERTIFICATE_REJECTED
            }
            cause = cause.cause
        }
        return e.message ?: "Connection failed"
    }

    private fun createSslContext(): SSLContext {
        val trustManager: X509TrustManager = if (certificatePinning) {
            PinningTrustManager()
        } else {
            TrustAllManager
        }
        val keyManagers = clientCert?.let { cert ->
            clientKey?.let { key ->
                buildKeyManagers(cert, key)
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context
    }

    /**
     * Trust-all behaviour used when certificate pinning is disabled. Mumble
     * servers commonly use self-signed certificates, so the fingerprint is
     * captured instead ([captureFingerprint]) and verification is left to the
     * user via the pinning option.
     */
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * Trust manager implementing the `certificatePinning` option: the server
     * certificate is trusted only when its SHA-256 fingerprint matches the
     * pinned fingerprint ([activePinnedFingerprint]). The first connection
     * (no pin yet) is accepted silently so the caller can pin the captured
     * fingerprint. On a mismatch the handshake is paused and
     * [MumbleListener.onCertificateError] asks the user to update the pin,
     * trust the certificate once, or reject the connection.
     */
    private inner class PinningTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certs = chain?.takeIf { it.isNotEmpty() }
                ?: throw CertificateException("Server did not present a certificate")
            val fingerprint = sha256Fingerprint(certs[0])
            serverFingerprint = fingerprint

            val pinned = activePinnedFingerprint?.takeIf { it.isNotBlank() } ?: return
            if (normalized(fingerprint) == normalized(pinned)) return

            // Mismatch: pause the handshake and ask the user. The gate is
            // marked open *before* the prompt so a fast user response can
            // never be lost between asking and waiting.
            certificateGate.open()
            listener.onCertificateError(fingerprint, pinned, certificateGate::resolve)
            when (certificateGate.await()) {
                CertificateDecision.UPDATE_PIN -> {
                    // The caller re-pins the new fingerprint for future
                    // sessions; trust it for the rest of this session too.
                    activePinnedFingerprint = fingerprint
                }
                CertificateDecision.TRUST_ONCE -> Unit
                CertificateDecision.REJECT ->
                    throw CertificateRejected(CERTIFICATE_REJECTED)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * One-shot gate pausing the handshake thread while the user reviews a
     * certificate problem. [abort] releases a pending wait so [close] can
     * never leave the connect thread blocked forever.
     */
    private inner class CertificateGate {
        private val latch = CountDownLatch(1)
        private var open = false

        @Volatile
        var decision: CertificateDecision = CertificateDecision.REJECT
            private set

        /** Marks the gate as pending; called before the prompt is raised. */
        fun open() {
            synchronized(this) { open = true }
        }

        /** Blocks until [resolve] or [abort], then returns the decision. */
        fun await(): CertificateDecision {
            latch.await()
            return decision
        }

        /** Delivers the user's decision; ignored when no prompt is pending. */
        fun resolve(d: CertificateDecision) {
            synchronized(this) {
                if (!open) return
                decision = d
                open = false
            }
            latch.countDown()
        }

        /** Releases a pending wait with [CertificateDecision.REJECT]. */
        fun abort() {
            synchronized(this) {
                if (!open) return
                decision = CertificateDecision.REJECT
                open = false
            }
            latch.countDown()
        }
    }

    /** Upper-case, colon-separated SHA-256 fingerprint of [cert]. */
    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /** Normalises a fingerprint for comparison (case / separator insensitive). */
    private fun normalized(fingerprint: String): String =
        fingerprint.replace(":", "").uppercase()

    /** Builds a [KeyManagerFactory] presenting the user's client certificate. */
    private fun buildKeyManagers(cert: X509Certificate, key: java.security.PrivateKey): Array<KeyManager> {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val store = java.security.KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("client", key, null, arrayOf(cert))
        factory.init(store, null)
        return factory.keyManagers
    }

    private fun captureFingerprint(socket: SSLSocket) {
        try {
            val session = socket.session
            val certs = session.peerCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                serverFingerprint = sha256Fingerprint(certs[0] as X509Certificate)
            }
            captureTlsSession(socket)
        } catch (e: Exception) {
            Log.w(TAG, "Could not capture certificate", e)
        }
    }

    private fun captureTlsSession(socket: SSLSocket) {
        try {
            val session = socket.session
            tlsProtocol = session.protocol.orEmpty()
            tlsCipherSuite = session.cipherSuite.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read TLS session", e)
        }
    }

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
                handleMessage(type, body)
            } catch (e: Exception) {
                disconnect(e.message ?: "Read error")
                return
            }
        }
    }

    private fun handleMessage(type: Int, body: ByteArray) {
        when (type) {
            MessageType.VERSION -> {
                // The server's version decides the negotiated UDP framing
                // (protobuf framing for servers >= 1.5.0).
                val v = Version.parseFrom(body)
                serverVersionV2 = v.versionV2
                serverVersionLegacy = v.versionV1
                serverRelease = if (v.hasRelease()) v.release else ""
                serverOs = if (v.hasOs()) v.os else ""
                serverOsVersion = if (v.hasOsVersion()) v.osVersion else ""
                listener.onServerVersion(v.versionV2, v.versionV1)
            }
            MessageType.SERVER_SYNC -> {
                val sync = ServerSync.parseFrom(body)
                localSession = sync.session
                if (sync.permissions != 0L) {
                    listener.onPermissionQuery(0, sync.permissions.toInt(), false)
                }
                listener.onConnected(localSession, sync.welcomeText, sync.maxBandwidth)
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
                listener.onPermissionQuery(pq.channelId, pq.permissions, pq.flush)
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
                // grow without bound (`now - originalTimestamp`).
                val ping = Ping.parseFrom(body)
                inFlightTcpPings.set(0)
                // Official `tTimestamp` is a QElapsedTimer (monotonic). Wall
                // time would let NTP steps land inside the 60 s window and
                // pollute tcpPingAvg / tcpPingVar.
                val now = SystemClock.elapsedRealtime()
                val ts = ping.timestamp
                if (ts in 1 until now) {
                    val rtt = now - ts
                    if (rtt < 60_000) {
                        tcpPingListener?.onTcpPingReply(rtt)
                    }
                }
                // Surface the server-reported crypt statistics (its view of the
                // UDP packets we sent). The official client uses exactly these
                // counters to decide whether to fall back to TCP mode.
                remoteCryptGood = ping.good
                remoteCryptLate = ping.late
                remoteCryptLost = ping.lost
                remoteCryptResync = ping.resync
                pingStatsListener?.invoke(ping.good, ping.lost)
            }
            MessageType.PLUGIN_DATA_TRANSMISSION -> {
                // Proto is vendored, but plugin IPC is out of scope.
                Log.d(TAG, "Ignoring PluginDataTransmission (${body.size} bytes)")
            }
            else -> {
                Log.d(TAG, "Unhandled message type $type (${body.size} bytes)")
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
    private fun buildPingWithStats(timestamp: Long): dev.woms.mumdroid.core.proto.Ping {
        val builder = dev.woms.mumdroid.core.proto.Ping.newBuilder()
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
        sendMessage(MessageType.ACL, ChannelPasswordAcl.query(channelId))
    }

    /** Desktop `ACLEditor::accept` ACL write (query unset). */
    fun sendAcl(msg: ACL) {
        sendMessage(MessageType.ACL, msg)
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
     * have, so they must not outlive the socket.
     */
    private fun clearSessionIdentity() {
        serverFingerprint = null
        serverVersionV2 = 0
        serverVersionLegacy = 0
        serverRelease = ""
        serverOs = ""
        serverOsVersion = ""
        tlsProtocol = ""
        tlsCipherSuite = ""
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
        certificateGate.abort()
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

/**
 * Thrown from the trust manager when the user (or a closed session) rejects
 * the server certificate while pinning is enabled. [MumbleClient] unwraps
 * this type to report a clean disconnect reason.
 */
private class CertificateRejected(message: String) : CertificateException(message)
