package dev.woms.mumdroid.core.net

import android.util.Log
import com.google.protobuf.MessageLite
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.core.net.MumbleClient.Companion.BODY_CHUNK_BYTES
import dev.woms.mumdroid.core.proto.Authenticate
import dev.woms.mumdroid.core.proto.Ping
import dev.woms.mumdroid.core.proto.Version
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * The Mumble protocol client. Owns the TCP/TLS control connection: the
 * connect / teardown lifecycle, message framing with the serialized write
 * path, and the TCP ping heartbeat.
 *
 * Focused collaborators own the individual policies:
 *  - [ClientTlsPolicy] — trust / certificate-pinning policy, the
 *    certificate-mismatch gate, and fingerprint / TLS-session capture,
 *  - [MumbleMessageRouter] — inbound message parsing and listener fan-out
 *    (this class implements its [MumbleMessageRouter.Host] to receive the
 *    stateful Version / ServerSync / Ping values),
 *  - [MumbleControlSenders] — outbound typed message assembly, mixed in by
 *    delegation so the typed sender API (official `ServerHandler` surface)
 *    stays reachable as `client.kickUser(...)`.
 *
 * The client itself has no voice-channel crypt state: the OCB2 counters and
 * UDP RTT statistics reported in the TCP Ping come from [statsProvider].
 */
class MumbleClient internal constructor(
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
    /**
     * Message assembly for the typed sender API (official `ServerHandler`),
     * mixed into this class by delegation. Must be a constructor parameter: a
     * delegating supertype is initialized before the class body, so it cannot
     * read a property declared further down.
     */
    private val controlSenders: MumbleControlSenders = MumbleControlSenders(),
) : MumbleMessageRouter.Host, MumbleControlSender by controlSenders {

    init {
        controlSenders.attach(object : MumbleControlSenders.Host {
            override fun writeMessage(type: Int, message: MessageLite) =
                this@MumbleClient.sendMessage(type, message)

            override fun writeBytes(type: Int, body: ByteArray) =
                this@MumbleClient.sendBytes(type, body)

            override fun localSession(): Int = this@MumbleClient.currentSession

            override fun accessTokens(): MutableList<String> = this@MumbleClient.accessTokens
        })
    }

    companion object {
        private const val TAG = "MumbleClient"
        private const val TIMEOUT_MS = 15000

        /**
         * How long a frame already in progress may go without a single byte
         * before the peer is considered wedged. Waiting for a frame to *start*
         * stays unbounded on purpose: a quiet control channel is normal, and a
         * peer that stops answering altogether is already reaped by the ping
         * in-flight budget. This covers the remaining case — a peer that keeps
         * answering pings while never finishing a declared message — which
         * would otherwise wedge the reader forever with `soTimeout == 0`.
         * `soTimeout` applies per read call, so a large body that keeps
         * arriving, however slowly, is unaffected.
         */
        private const val FRAME_STALL_TIMEOUT_MS = 30_000

        // Official desktop default (`iPingIntervalMsec`) is 5 seconds. A 15 s
        // interval plus a 15 s SO_TIMEOUT raced the keep-alive and dropped
        // otherwise-healthy connections.
        private const val PING_INTERVAL_SECONDS = 5L
        /** Official `iMaxInFlightTCPPings` default: drop the connection after this many unanswered pings. */
        private const val MAX_IN_FLIGHT_TCP_PINGS = 4
        /** Official TCP frame cap (`Connection.cpp`: `iPacketLength > 0x7fffff`).
         *  USER_STATE with a large avatar texture or comment can exceed 1 MB. */
        private const val MAX_TCP_MESSAGE_BYTES = 0x7fffff

        /**
         * Bodies larger than this are read in chunks instead of allocating the
         * declared length up front. The official client only reads a body once
         * the socket has the whole frame buffered (`Connection.cpp`:
         * `iAvailable < iPacketLength`); a blocking socket cannot know that,
         * but growing on demand keeps the memory held proportional to the bytes
         * actually received, so a hostile server has to send them instead of
         * pinning the 8 MiB cap with a 6-byte header.
         */
        private const val BODY_CHUNK_BYTES = 64 * 1024

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

        /**
         * Reads a message body of [size] bytes: small bodies land in one
         * exact-size array, larger ones grow on demand (doubling from
         * [BODY_CHUNK_BYTES]) so the memory held tracks the bytes actually
         * received, while the returned array is still exactly [size] bytes and
         * nothing beyond it is consumed from [input].
         *
         * @throws EOFException when the peer closes the connection mid-message.
         */
        internal fun readMessageBody(input: DataInputStream, size: Int): ByteArray {
            if (size <= BODY_CHUNK_BYTES) {
                val body = ByteArray(size)
                input.readFully(body)
                return body
            }
            var body = ByteArray(BODY_CHUNK_BYTES)
            var filled = 0
            while (filled < size) {
                if (filled == body.size) {
                    body = body.copyOf(minOf(size, body.size shl 1))
                }
                val n = input.read(body, filled, body.size - filled)
                if (n < 0) throw EOFException("Connection closed mid-message")
                filled += n
            }
            return body
        }
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

    /** Keep-alive pings and their in-flight budget / RTT accounting. */
    private val pingLoop = TcpPingLoop(
        tag = TAG,
        intervalSeconds = PING_INTERVAL_SECONDS,
        maxInFlight = MAX_IN_FLIGHT_TCP_PINGS,
        sendPing = { timestamp -> sendMessage(MessageType.PING, buildPingWithStats(timestamp)) },
        onTimeout = { disconnect("Server is not responding to TCP pings") },
        onRtt = { rtt -> tcpPingListener?.onTcpPingReply(rtt) },
    )
    /** Serializes TCP writes so UI-thread callers never touch the SSL socket. */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mumble-tcp-write").apply { isDaemon = true }
    }

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
            // Unbounded idle wait from here on: a quiet control channel must not
            // drop the socket. The read loop re-arms a stalled-frame timeout
            // only while a frame is actually being received.
            rawSocket.soTimeout = 0

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

    /**
     * Sends a raw payload with the 6-byte big-endian header.
     *
     * Off the main thread the write happens inline; on the main thread it is
     * handed to [writeExecutor] so the UI never blocks on the socket. A queued
     * write can still lose the race against [close] — that drop is logged by
     * [writeLocked] instead of passing unnoticed.
     */
    fun sendBytes(type: Int, body: ByteArray) {
        if (!running.get()) {
            Log.i(TAG, "TCP send dropped (not running): type $type")
            return
        }
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
        // A task queued while running can only get to run after close() has
        // flipped `running` (close() shuts the executor down without draining).
        // The socket is going away by then, so write nothing on it and say so,
        // rather than failing silently or racing the socket close.
        if (!running.get()) {
            Log.i(TAG, "TCP write dropped (closing): type $type")
            return
        }
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
                // Idle wait before any frame starts: unbounded, so a quiet but
                // healthy control channel is never dropped.
                val first = input.read()
                if (first < 0) {
                    disconnect("Connection closed")
                    return
                }
                // A frame is now in progress: bound the wait for the rest of it
                // so a peer that stalls mid-message cannot wedge this loop.
                armFrameReadTimeout()
                try {
                    val type = (first shl 8) or input.readUnsignedByte()
                    val size = input.readInt()
                    if (size < 0 || size > MAX_TCP_MESSAGE_BYTES) {
                        disconnect("Invalid message size")
                        return
                    }
                    router.dispatch(type, readMessageBody(input, size))
                } finally {
                    clearFrameReadTimeout()
                }
            } catch (e: Exception) {
                disconnect(e.message ?: "Read error")
                return
            }
        }
    }

    /** Bounds a single read while a frame is in progress; see [readLoop]. */
    private fun armFrameReadTimeout() {
        try {
            socket?.soTimeout = FRAME_STALL_TIMEOUT_MS
        } catch (_: Exception) {
            // A socket that is already gone will fail the read itself.
        }
    }

    /** Restores the unbounded idle wait once a frame is done (or has failed). */
    private fun clearFrameReadTimeout() {
        try {
            socket?.soTimeout = 0
        } catch (_: Exception) {
        }
    }

    private fun startPingLoop() = pingLoop.start()

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
        // Official `tTimestamp` is a QElapsedTimer (monotonic); the clock and
        // the 60 s staleness window live in [TcpPingLoop].
        pingLoop.onReply(timestampMs)
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
        // Release a pending certificate prompt so the handshake thread cannot
        // block forever on a dialog nobody will answer any more.
        tls.abort()
        pingLoop.stop()
        // Queued writes are abandoned rather than drained: the socket is closed
        // right below, so they could not reach the server anyway, and the app
        // never sends a message that has to survive teardown (no Disconnect
        // message — teardown is just an explicit FIN). `running` is already
        // false, so those tasks turn into a logged drop in writeLocked instead
        // of a silent one, and shutdownNow() additionally unblocks an in-flight
        // SSL write so close() cannot hang on a stuck socket.
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
