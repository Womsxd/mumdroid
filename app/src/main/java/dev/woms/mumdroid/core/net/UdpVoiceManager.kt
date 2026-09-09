package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.OpusImplementation
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the UDP voice channel used to send and receive encrypted voice and
 * ping traffic, mirroring the official Mumble client's transport behaviour.
 *
 * This class owns the transport concerns only: socket lifecycle, the receive
 * loop with peer filtering, and the send path with its OCB2 send buffer. The
 * protocol policy lives in dedicated collaborators:
 *  - [VoiceFraming] — legacy/protobuf body build and decode (both protocol
 *    generations; see its KDoc for the framing layouts),
 *  - [UdpVoiceCrypto] — the OCB2 state plus the official 5-second
 *    decryption-failure resync rule,
 *  - [UdpPingTracker] — UDP round-trip-time statistics.
 *
 * As in the official client, only Opus audio is decoded; the obsolete CELT /
 * Speex codecs are dropped. Pings are sent periodically to detect UDP
 * connectivity and measure round-trip time.
 */
class UdpVoiceManager(
    private val host: String,
    private val port: Int,
    opusImplementation: OpusImplementation = OpusImplementation.LIBOPUS,
) {
    companion object {
        private const val TAG = "UdpVoiceManager"
        // Matches the official `MAX_UDP_PACKET_SIZE` (murmur/MumbleProtocol.h):
        // 1024. A larger bound would let a spoofed oversized datagram be read
        // in full and pushed through OCB2, which costs one AES block op per
        // 16-byte block (256 AES for 4096 B vs. 64 for 1024 B). Aligning with
        // the server also keeps behaviour identical: murmur drops any packet
        // with `len > MAX_UDP_PACKET_SIZE`.
        private const val MAX_PACKET = 1024
        // Mirror the official client's ping cadence: the desktop `iPingIntervalMsec`
        // defaults to 5000 ms. Sending every second only wastes bandwidth on the
        // voice channel, so we align with the official 5-second interval.
        private const val PING_INTERVAL_MS = 5_000L
        private const val RECEIVE_POLL_MS = 250

        /**
         * Official `udpReady` drops datagrams whose source is not the TCP
         * peer (`HostAddress` equality, which treats IPv4-mapped IPv6 as IPv4).
         */
        internal fun peerMatches(
            packetAddr: InetAddress,
            packetPort: Int,
            peerAddr: InetAddress,
            peerPort: Int,
        ): Boolean {
            if (packetPort != peerPort) return false
            if (packetAddr == peerAddr) return true
            val a = ipv4Bytes(packetAddr) ?: return false
            val b = ipv4Bytes(peerAddr) ?: return false
            return a.contentEquals(b)
        }

        private fun ipv4Bytes(addr: InetAddress): ByteArray? {
            when (addr) {
                is Inet4Address -> return addr.address
                is Inet6Address -> {
                    val bytes = addr.address
                    if (bytes.size != 16) return null
                    for (i in 0..9) if (bytes[i] != 0.toByte()) return null
                    if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return null
                    return bytes.copyOfRange(12, 16)
                }
            }
            return null
        }
    }

    interface Listener {
        /**
         * Encoded Opus from a remote user, stamped with official
         * `frameNumber` (10 ms units). Playback reorders and conceals.
         */
        fun onAudioPacket(
            session: Int,
            frameNumber: Long,
            payload: ByteArray,
            isLastFrame: Boolean,
        ) {}

        /** A UDP ping round-trip time measurement, in milliseconds. */
        fun onUdpPing(rttMillis: Long)

        /** UDP connectivity established. */
        fun onUdpConnected()

        /** UDP error. */
        fun onUdpError(message: String)

        /**
         * Talk-state change for [session], mirroring official
         * `ClientUser::setTalking` driven from the audio path (not UserState).
         */
        fun onTalking(session: Int, talking: Boolean) {}
    }

    /** Monotonic source for ping timestamps and local timeouts (official `QElapsedTimer`). */
    private val clock = { SystemClock.elapsedRealtime() }

    private val crypto = UdpVoiceCrypto(clock)
    private val framing = VoiceFraming(clock)
    private val pingTracker = UdpPingTracker()
    private val sendLock = Any()
    private val encryptPacket = ByteArray(MAX_PACKET)
    private val opus = OpusCodec(opusImplementation)
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null
    @Volatile
    private var listener: Listener? = null

    /** Last UDP ping send; 0 until the receive loop has entered `receive()`. */
    private var lastPingSentMs = 0L

    /** TCP peer used for `sendto` / source filtering. Official does not `connect()`. */
    @Volatile
    private var peerAddress: InetAddress? = null
    @Volatile
    private var peerPort: Int = 0

    /** Opus encode bitrate in bits-per-second (0 = codec default). */
    @Volatile
    var bitrate: Int = 0

    /**
     * The number of 10 ms Opus frames bundled into each packet, mirroring the
     * desktop "Audio per packet" setting (2 = 20 ms). Applied to the codec.
     */
    @Volatile
    var framesPerPacket: Int = 2

    /** Enables the low-latency Opus application mode (mirrors the desktop). */
    @Volatile
    var lowLatency: Boolean = false

    /** Marks the voice socket for low-latency prioritisation (QoS). */
    var qualityOfService: Boolean = false

    /**
     * The UDP framing negotiated with the server: `true` for the protobuf
     * framing of Mumble >= 1.5.0 servers, `false` for the legacy framing.
     * See [VoiceFraming.protobufMode].
     */
    var protobufMode: Boolean
        get() = framing.protobufMode
        set(value) {
            framing.protobufMode = value
        }

    /**
     * Invoked when UDP decryption keeps failing (mirrors the official client's
     * 5-second rule); the owner should send an empty CryptSetup over TCP to
     * request a nonce resync from the server. See [UdpVoiceCrypto].
     */
    var onRequestCryptResync: (() -> Unit)?
        get() = crypto.onRequestCryptResync
        set(value) {
            crypto.onRequestCryptResync = value
        }

    /** Whether the UDP voice channel has been started. */
    val isRunning: Boolean get() = running.get()

    /** Average UDP ping round-trip time in milliseconds (0 when no ping yet). */
    val averageUdpPing: Long
        get() = pingTracker.meanMillis

    /** Number of UDP ping round-trips measured so far. */
    val udpPingCount: Int
        get() = pingTracker.count

    /** Population variance of measured UDP RTTs, in ms². */
    val udpPingVariance: Float
        get() = pingTracker.varianceMillisSquared

    /** Registers the playback listener without opening the UDP socket (force-TCP). */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Sets the OCB2 key and the client/server nonces from CryptSetup.
     * A full delivery is a fresh crypto context: [UdpVoiceCrypto.setup] clears
     * the replay history and packet statistics, so key rotations and
     * re-delivered setups cannot inherit state from a previous session.
     */
    fun setupCryptography(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) =
        crypto.setup(key, clientNonce, serverNonce)

    /**
     * Adopts a new decryption IV delivered via CryptSetup resync
     * (official `msgCryptSetup`: size check, `m_statsLocal.resync++`,
     * `setDecryptIV`).
     */
    fun resyncDecryptIV(iv: ByteArray): Boolean = crypto.resyncDecryptIV(iv)

    /** The current encryption IV, or null when crypto is not ready. */
    fun encryptIV(): ByteArray? = crypto.encryptIV()

    /** @return the legacy OCB2 voice-packet statistics (good/late/lost/resync)
     *          accumulated by the decrypt path, so they can be reported in the
     *          TCP Ping (the PC admin's user info shows them). */
    fun packetStats(): UdpVoiceCrypto.CryptStats = crypto.packetStats()

    /** @return whether the OCB2 crypto state is ready to encrypt/decrypt. */
    fun isCryptoReady(): Boolean = crypto.isReady

    /**
     * Applies the configured Opus encode settings (bitrate, frame size and
     * low-latency mode) to the codec. 0 bitrate means codec default.
     */
    fun applyBitrate() {
        opus.setFrameSize(OpusCodec.FRAME_SIZE_10MS * framesPerPacket.coerceIn(1, 6))
        opus.setLowLatency(lowLatency)
        if (bitrate > 0) {
            opus.setBitrate(bitrate)
        }
    }

    /** Encodes a PCM frame into an Opus payload. */
    fun encodeOpus(pcm: ShortArray): ByteArray? = opus.encode(pcm)

    /** Switches the Opus encode/decode backend and re-applies bitrate settings. */
    fun setOpusImplementation(implementation: OpusImplementation) {
        opus.setImplementation(implementation)
        applyBitrate()
    }

    /** Official `OPUS_RESET_STATE` at the start of a talk spurt. */
    fun resetEncoder() = opus.resetEncoder()

    private fun outgoingFrameCount(sampleCount: Int? = null): Int {
        val samples = sampleCount ?: (OpusCodec.FRAME_SIZE_10MS * framesPerPacket.coerceIn(1, 6))
        return OpusCodec.encodedTenMsFrames(samples).coerceAtLeast(1)
    }

    /**
     * Opens the UDP voice socket. When [bindAddress] is set (the TCP socket's
     * local address) the datagram socket is bound to the same interface,
     * matching the official `bUdpForceTcpAddr` default.
     */
    @JvmOverloads
    fun start(
        listener: Listener? = this.listener,
        bindAddress: InetAddress? = null,
        remoteAddress: InetAddress? = null,
    ) {
        if (listener != null) this.listener = listener
        val callback = this.listener
        if (!running.compareAndSet(false, true)) return
        try {
            val sock = DatagramSocket(null)
            if (bindAddress != null && !bindAddress.isAnyLocalAddress) {
                sock.bind(InetSocketAddress(bindAddress, 0))
            } else {
                sock.bind(InetSocketAddress(0))
            }
            // Official `QUdpSocket::writeDatagram` — never connect(). A
            // connected DatagramSocket turns ICMP errors into receive()
            // exceptions and drops replies that are IPv4-mapped.
            val dest = if (remoteAddress != null) {
                InetSocketAddress(remoteAddress, port)
            } else {
                InetSocketAddress(host, port)
            }
            val resolved = dest.address ?: throw IllegalStateException("UDP peer unresolved")
            peerAddress = resolved
            peerPort = dest.port
            socket = sock
            applyBitrate()
            if (qualityOfService) {
                try {
                    sock.trafficClass = 0xE0
                } catch (_: Exception) {
                    try {
                        sock.trafficClass = 0x80
                    } catch (_: Exception) {
                    }
                }
            }
            receiveThread = Thread({ receiveLoop() }, "udp-voice").apply { start() }
            callback?.onUdpConnected()
        } catch (e: Exception) {
            running.set(false)
            peerAddress = null
            peerPort = 0
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            callback?.onUdpError(e.message ?: "UDP connect failed")
        }
    }

    private fun receiveLoop() {
        val sock = socket ?: return
        try {
            sock.soTimeout = RECEIVE_POLL_MS
        } catch (_: Exception) {
        }
        val buffer = ByteArray(MAX_PACKET)
        // Do not ping until `receive()` has run: a reply that lands before
        // that is dropped by the kernel and desyncs OCB2.
        var receivePrimed = false
        while (running.get()) {
            if (receivePrimed) maybeSendPing()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                receivePrimed = true
                val from = packet.address ?: continue
                val expected = peerAddress
                if (expected == null || !peerMatches(from, packet.port, expected, peerPort)) {
                    continue
                }
                try {
                    handlePacket(packet.data, packet.length)
                } catch (e: Exception) {
                    Log.e(TAG, "UDP packet processing error", e)
                }
            } catch (_: SocketTimeoutException) {
                receivePrimed = true
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "UDP receive error", e)
                if (sock.isClosed) {
                    listener?.onUdpError(e.message ?: "UDP socket closed")
                    break
                }
            }
        }
    }

    private fun maybeSendPing() {
        if (!crypto.isReady || !running.get()) return
        val now = clock()
        // Prime the clock on the first pass so the first ping waits a full
        // interval (official TCP ticker). Sending immediately after bind
        // still races a fast reply into the gap before the next receive().
        if (lastPingSentMs == 0L) {
            lastPingSentMs = now
            return
        }
        if (now - lastPingSentMs < PING_INTERVAL_MS) return
        lastPingSentMs = now
        sendPing()
    }

    private fun handlePacket(data: ByteArray, length: Int) {
        if (length < 5) return
        // Drop anything above the official `MAX_UDP_PACKET_SIZE` before it can
        // be fed to OCB2 (murmur does the same `len > MAX_UDP_PACKET_SIZE ->
        // continue`). Truncating at the buffer bound would otherwise still run
        // AES over a partial forged datagram; an explicit cap keeps the worst
        // case identical to the server's and bounds the per-packet decrypt work.
        if (length > MAX_PACKET) return
        // The datagram is `[4-byte OCB2 overhead][ciphertext]`; the framing
        // header byte is inside the encrypted payload, so decrypt the whole
        // datagram first (the 5-second resync rule lives in [UdpVoiceCrypto]).
        val plain = crypto.decrypt(data, 0, length) ?: return
        when (val decoded = framing.decodeDatagram(plain)) {
            is VoiceFraming.Decoded.Ping -> recordPingRtt(decoded.timestamp)
            is VoiceFraming.Decoded.Audio -> handleDecodedFrame(
                decoded.session,
                decoded.frameNumber,
                decoded.payload,
                decoded.isLastFrame,
            )
            VoiceFraming.Decoded.Unknown ->
                if (plain.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Ignoring unknown UDP voice packet header=0x%02x"
                            .format(plain[0].toInt() and 0xff),
                    )
                }
        }
    }

    private fun handleDecodedFrame(session: Int, frameNumber: Long, payload: ByteArray, isLastFrame: Boolean) {
        // Do not decode or conceal here. Official AudioOutputSpeech puts the
        // encoded packet into the jitter buffer with
        // `timestamp = iFrameSize * frameNumber` and decodes in timestamp
        // order at playback. Receive-time PLC with a +1 increment treated
        // every official 20 ms packet (seq 0, 2, 4…) as a loss.
        listener?.onAudioPacket(session, frameNumber, payload, isLastFrame)
        listener?.onTalking(session, !isLastFrame)
    }

    /**
     * Plays a plaintext UDPTunnel body (force-TCP fallback). The dispatch in
     * [VoiceFraming.decodeTunneled] is framing-lenient so a fallback session
     * still plays when the negotiated framing disagrees with the tunneled
     * header.
     */
    fun playTunneled(body: ByteArray) {
        val decoded = framing.decodeTunneled(body) ?: return
        handleDecodedFrame(decoded.session, decoded.frameNumber, decoded.payload, decoded.isLastFrame)
    }

    /**
     * Records a ping round trip: the server echoes the timestamp we sent, so
     * the RTT is `now - timestamp`. Echoes that cannot be tied to one of our
     * recent pings (clock skew, bogus echo) are dropped by
     * [UdpPingTracker.record] instead of being recorded as 0 ms samples.
     */
    private fun recordPingRtt(echoedTimestamp: Long) {
        if (echoedTimestamp <= 0) return
        val rtt = clock() - echoedTimestamp
        if (pingTracker.record(rtt)) {
            listener?.onUdpPing(rtt)
        }
    }

    /**
     * Sends a UDP ping packet (OCB2-encrypted). Uses the protobuf Ping
     * framing for Mumble >= 1.5.0 servers and the legacy
     * `[header][varint timestamp]` framing otherwise.
     */
    private fun sendPing() {
        if (!crypto.isReady || !running.get()) return
        encryptAndSend(framing.pingBody())
    }

    /**
     * Encrypts a pre-built plaintext voice body and writes it to UDP.
     * Used by the unified send path so TCP fallback can reuse the same body
     * (and sequence number) without encoding twice.
     */
    fun sendPlaintextUdp(body: ByteArray): Boolean {
        if (!crypto.isReady || socket == null) return false
        return encryptAndSend(body)
    }

    /**
     * Encrypts [plain] into the reused send buffer and writes the datagram.
     * Capture and ping threads share [encryptPacket], so this is serialised.
     */
    private fun encryptAndSend(plain: ByteArray): Boolean {
        synchronized(sendLock) {
            val n = crypto.encrypt(plain, encryptPacket)
            if (n < 0) return false
            return sendDatagram(encryptPacket, n)
        }
    }

    /** Writes an already-encrypted datagram. UDP pings use this (`force` in official). */
    private fun sendDatagram(packetData: ByteArray, length: Int = packetData.size): Boolean {
        val sock = socket ?: return false
        val dest = peerAddress ?: return false
        return try {
            sock.send(DatagramPacket(packetData, length, dest, peerPort))
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error", e)
            false
        }
    }

    /**
     * Encodes one frame of digital silence — the payload used by the official
     * client for its end-of-transmission packet.
     *
     * @return Opus bytes and the 10 ms frame count that [encode] actually
     *         consumed (not the configured [framesPerPacket], which is not a
     *         legal Opus size at 30/50 ms).
     */
    fun encodeSilence(): Pair<ByteArray, Int>? {
        val pcm = ShortArray(opus.getFrameSize())
        val encoded = opus.encode(pcm) ?: return null
        val frames = OpusCodec.encodedTenMsFrames(pcm.size).coerceAtLeast(1)
        return encoded to frames
    }

    /**
     * Builds the plaintext body of a force-TCP UDPTunnel message. TCP is
     * already TLS-encrypted, so the full voice packet is sent WITHOUT OCB2
     * encryption, mirroring the official client's force-TCP branch.
     */
    @JvmOverloads
    fun buildTunnelPacket(
        payload: ByteArray,
        isLastFrame: Boolean = false,
        frameCount: Int = outgoingFrameCount(),
    ): ByteArray = framing.buildVoiceBody(payload, isLastFrame, frameCount)

    /**
     * Closes the datagram socket and ping loop but keeps crypto/codec so
     * voice can continue over TCP tunnel (force-TCP / UDP fallback).
     */
    fun stopDatagram() {
        running.set(false)
        lastPingSentMs = 0L
        peerAddress = null
        peerPort = 0
        val thread = receiveThread
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        receiveThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        stopDatagram()
        // Full teardown: wipe the OCB2 key/history/stats so no crypto state
        // survives across sessions. (stopDatagram above deliberately keeps
        // crypto for the TCP-tunnel fallback — only close() discards it.)
        crypto.reset()
        framing.reset()
        opus.close()
    }
}
