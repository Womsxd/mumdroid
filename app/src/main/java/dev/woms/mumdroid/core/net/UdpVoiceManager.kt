package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceFrameCounter
import dev.woms.mumdroid.core.crypto.CryptOCB2
import dev.woms.mumdroid.core.crypto.CryptState
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
 * ping traffic, mirroring the official Mumble client behaviour.
 *
 * ## Framing
 * The whole packet (header byte + payload) is encrypted with the OCB2
 * [CryptState] format, producing a datagram of
 * `[4-byte OCB2 overhead][enc(header|payload)]` — the encryption is identical
 * for both protocol generations (the official client uses `CryptStateOCB2`
 * for the protobuf-framed protocol as well).
 *
 * After decryption the leading byte selects the framing:
 *  - **Legacy** (`protobufMode == false`, servers < 1.5.0):
 *    `(type << 5) | target/context` where the type selects the message kind:
 *     0 = CELT Alpha, 1 = Ping, 2 = Speex, 3 = CELT Beta, 4 = Opus.
 *  - **Protobuf** (`protobufMode == true`, servers >= 1.5.0):
 *    the header byte directly selects the message type
 *    ([ProtoUdpCodec.HEADER_AUDIO] = Audio, [ProtoUdpCodec.HEADER_PING] = Ping)
 *    followed by a serialized MumbleUDP protobuf message.
 *
 * As in the official client, only Opus audio is decoded; the obsolete CELT /
 * Speex codecs are dropped. Pings are sent periodically to detect UDP
 * connectivity and measure round-trip time.
 */
class UdpVoiceManager(
    private val host: String,
    private val port: Int,
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

        /** If decryption keeps failing for this long, request a crypt resync. */
        private const val RESYNC_AFTER_MS = 5000L

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
        /** Decoded PCM from a remote user (legacy / tests). */
        fun onAudio(session: Int, pcm: ShortArray) {}

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

    private val crypt = CryptState()
    private val opus = OpusCodec()
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null
    @Volatile
    private var listener: Listener? = null

    /** Monotonic source for ping timestamps and local timeouts (official `QElapsedTimer`). */
    private val clock = { SystemClock.elapsedRealtime() }

    /** Last UDP ping send; 0 until the receive loop has entered `receive()`. */
    private var lastPingSentMs = 0L

    /** TCP peer used for `sendto` / source filtering. Official does not `connect()`. */
    @Volatile
    private var peerAddress: InetAddress? = null
    @Volatile
    private var peerPort: Int = 0

    /** Monotonic ms of the last successful UDP decryption (for resync detection). */
    @Volatile
    private var lastGoodUdpMs = 0L

    /** Official `tLastGood` starts when crypto is armed, not at first success. */
    @Volatile
    private var cryptoReadyMs = 0L

    /** Monotonic ms of the last crypt-resync request we issued. */
    @Volatile
    private var lastResyncRequestMs = 0L

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

    /** Whether UDP pings are sent periodically (enabled once crypto is ready). */
    @Volatile
    var pingEnabled: Boolean = true

    /**
     * The UDP framing negotiated with the server: `true` for the protobuf
     * framing of Mumble >= 1.5.0 servers, `false` for the legacy framing.
     * Determined from the server's reported protocol version.
     */
    @Volatile
    var protobufMode: Boolean = false

    /**
     * Invoked when UDP decryption keeps failing (mirrors the official client's
     * 5-second rule); the owner should send an empty CryptSetup over TCP to
     * request a nonce resync from the server.
     */
    @Volatile
    var onRequestCryptResync: (() -> Unit)? = null

    private var localSession = 0

    /**
     * Outgoing `frameNumber` in 10 ms units (official `iFrameCounter`).
     * A 20 ms packet is stamped N then advances to N+2.
     */
    private val frameCounter = VoiceFrameCounter()

    /** Whether the UDP voice channel has been started. */
    val isRunning: Boolean get() = running.get()

    /** Registers the playback listener without opening the UDP socket (force-TCP). */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Sets the OCB2 key and the client/server nonces from CryptSetup.
     * A full delivery is a fresh crypto context: [CryptState.setKey] clears
     * the replay history and packet statistics, so key rotations and
     * re-delivered setups cannot inherit state from a previous session.
     */
    fun setupCryptography(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        crypt.setKey(key, clientNonce, serverNonce)
        if (cryptoReadyMs == 0L) cryptoReadyMs = clock()
    }

    /**
     * Adopts a new decryption IV delivered via CryptSetup resync.
     * Mirrors official client `msgCryptSetup`: size check, then
     * `m_statsLocal.resync++`, then `setDecryptIV`. Full key delivery
     * must go through [setupCryptography] instead.
     */
    fun resyncDecryptIV(iv: ByteArray): Boolean {
        if (iv.size != CryptOCB2.NONCE_SIZE) return false
        crypt.incrementResync()
        return crypt.setDecryptIV(iv)
    }

    /** The current encryption IV, or null when crypto is not ready. */
    fun encryptIV(): ByteArray? = if (crypt.isReady) crypt.getEncryptIV() else null

    /** @return the legacy OCB2 packet statistics (good/late/lost/resync)
     *          accumulated by the decrypt path, so they can be reported in the
     *          TCP Ping (the PC admin's user info shows them). */
    fun packetStats(): CryptStats = CryptStats(
        good = crypt.goodPackets,
        late = crypt.latePackets,
        lost = crypt.lostPackets,
        resync = crypt.resyncPackets,
    )

    /** Legacy OCB2 voice-packet counters (good/late/lost/resync). */
    data class CryptStats(val good: Int, val late: Int, val lost: Int, val resync: Int)

    /** Average UDP ping round-trip time in milliseconds (0 when no ping yet). */
    val averageUdpPing: Long
        get() = synchronized(pingStatsLock) {
            if (udpPingSamples > 0) udpPingTotal / udpPingSamples else 0L
        }

    /** Number of UDP ping round-trips measured so far. */
    val udpPingCount: Int
        get() = synchronized(pingStatsLock) { udpPingSamples }

    /** Population variance of measured UDP RTTs, in ms². */
    val udpPingVariance: Float
        get() = synchronized(pingStatsLock) {
            if (udpPingSamples <= 0) {
                0f
            } else {
                val mean = udpPingTotal.toDouble() / udpPingSamples
                (udpPingSumSq / udpPingSamples - mean * mean).toFloat().coerceAtLeast(0f)
            }
        }

    // Running UDP RTT accumulator for reporting an average to the server.
    // Written by the UDP receive thread, read by the TCP ping thread
    // (buildConnectionStats) and the UI. Guarded by a lock rather than
    // atomics: the Double sum is two 32-bit halves without volatile on
    // 32-bit JVMs (torn reads), and the variance needs a consistent
    // cross-field snapshot — per-field atomics could pair a new samples
    // count with a stale sum. Lock traffic is negligible (one write per
    // ping, a few reads per second).
    private val pingStatsLock = Any()
    private var udpPingTotal = 0L
    private var udpPingSamples = 0
    private var udpPingSumSq = 0.0

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

    /** @return whether the OCB2 crypto state is ready to encrypt/decrypt. */
    fun isCryptoReady(): Boolean = crypt.isReady

    /** Encodes a PCM frame into an Opus payload. */
    fun encodeOpus(pcm: ShortArray): ByteArray? = opus.encode(pcm)

    /** Official `OPUS_RESET_STATE` at the start of a talk spurt. */
    fun resetEncoder() = opus.resetEncoder()

    /** Builds the legacy UDP header byte for a normal Opus talk packet. */
    fun talkHeaderByte(): Byte = UdpPacketCodec.talkHeaderByte()

    /**
     * Encrypts a complete legacy voice packet (header byte + payload) for the
     * UDP voice channel. The resulting datagram is
     * `[4-byte OCB2 overhead][enc(header|payload)]`, matching the official
     * `CryptStateOCB2::encrypt`.
     */
    fun encryptVoicePacket(header: Byte, payload: ByteArray): ByteArray? {
        if (!crypt.isReady) return null
        val plain = ByteArray(1 + payload.size)
        plain[0] = header
        System.arraycopy(payload, 0, plain, 1, payload.size)
        return crypt.encrypt(plain)
    }

    /**
     * Decrypts a complete legacy/protobuf voice datagram
     * (`[4-byte OCB2 overhead][ciphertext]`) and returns the plaintext
     * `[header|payload]`, or null on failure. Mirrors the official client's
     * 5-second rule: when decryption keeps failing, a crypt-nonce resync is
     * requested via [onRequestCryptResync].
     */
    fun decryptVoicePacket(packet: ByteArray): ByteArray? =
        decryptVoicePacket(packet, 0, packet.size)

    /**
     * Decrypts `packet[offset, offset+length)` without copying the datagram
     * first; [offset] is the start of the 4-byte OCB2 header.
     */
    fun decryptVoicePacket(packet: ByteArray, offset: Int, length: Int): ByteArray? {
        val plain = crypt.decrypt(packet, offset, length)
        if (plain != null) {
            lastGoodUdpMs = clock()
            lastResyncRequestMs = 0L
        } else if (isCryptoReady()) {
            val now = clock()
            // Official tLastGood starts at construction, so a never-successful
            // decrypt still resyncs after 5 s. lastGoodUdpMs==0 used to skip that.
            val baseline = if (lastGoodUdpMs != 0L) lastGoodUdpMs else cryptoReadyMs
            if (baseline != 0L && now - baseline > RESYNC_AFTER_MS) {
                val lastRequest = lastResyncRequestMs
                if (lastRequest == 0L || now - lastRequest > RESYNC_AFTER_MS) {
                    lastResyncRequestMs = now
                    onRequestCryptResync?.invoke()
                }
            }
        }
        return plain
    }

    /** Visible for tests: next unused outgoing 10 ms frame index. */
    internal fun peekOutSequence(): Long = frameCounter.value

    private fun outgoingFrameCount(sampleCount: Int? = null): Int {
        val samples = sampleCount ?: (OpusCodec.FRAME_SIZE_10MS * framesPerPacket.coerceIn(1, 6))
        return OpusCodec.tenMsFrames(samples).coerceAtLeast(1)
    }

    /** Decodes an Opus payload to PCM (legacy single-stream compat). */
    fun decodePcm(payload: ByteArray): ShortArray? = opus.decode(payload)

    /** Session-aware decode (preferred for multi-speaker). */
    fun decodePcm(session: Int, payload: ByteArray, isTerminator: Boolean = false): ShortArray? =
        opus.decodeForSession(session, payload, isTerminator)

    fun setLocalSession(session: Int) {
        localSession = session
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
        if (!pingEnabled || !crypt.isReady || !running.get()) return
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
        // datagram first.
        val plain = decryptVoicePacket(data, 0, length) ?: return
        if (plain.isEmpty()) return

        if (protobufMode) {
            handleProtobufPacket(plain)
        } else {
            handleLegacyPacket(plain)
        }
    }

    /** Handles a decrypted protobuf-framed (Mumble >= 1.5.0) packet. */
    private fun handleProtobufPacket(plain: ByteArray) {
        when (plain[0].toInt() and 0xff) {
            ProtoUdpCodec.HEADER_PING -> {
                val ts = ProtoUdpCodec.decodePing(plain, 1, plain.size - 1)
                if (ts != null && ts > 0) {
                    val rtt = clock() - ts
                    if (rtt in 0..60_000) {
                        recordPingRtt(rtt)
                    }
                }
            }
            ProtoUdpCodec.HEADER_AUDIO -> handleOpusAudio(plain)
            else -> Log.d(TAG, "Ignoring unknown protobuf UDP header=${plain[0].toInt()}")
        }
    }

    /** Handles a decrypted legacy-framed packet. */
    private fun handleLegacyPacket(plain: ByteArray) {
        // Extended legacy ping replies from the server are header-less 24-byte
        // blocks: [4B server version (BE)][8B echoed timestamp][4B users]
        // [4B max users][4B max bandwidth]. This check MUST come before the
        // protobuf-ping-header check below: the first byte is the most
        // significant byte of the BE server version, and every major=1
        // server (i.e. virtually all of them) carries 0x01 there — treating
        // the header check as first-class would swallow the extended ping as
        // a bogus protobuf ping (protobuf parsing is lenient, so it can
        // "succeed" with a garbage timestamp). The official UDPDecoder
        // (MumbleProtocol.cpp) checks the header first and returns the
        // protobuf result unconditionally, so it drops these extended pings
        // whenever the 23-byte tail happens to parse; routing by length here
        // is strictly more robust, and real protobuf pings never reach 24
        // bytes (a serialised Ping body tops out around 14 bytes), so the
        // size check is unambiguous.
        if (plain.size == 24) {
            handleExtendedPing(plain)
            return
        }

        // Mirrors the official UDPDecoder::decode (client role): in legacy mode
        // a packet whose first byte equals the *protobuf* ping header byte
        // (0x01) is treated as a protobuf-framed ping, since peers whose version
        // we have not negotiated may already speak the new framing.
        if (plain[0].toInt() == ProtoUdpCodec.HEADER_PING) {
            val ts = ProtoUdpCodec.decodePing(plain, 1, plain.size - 1)
            if (ts != null && ts > 0) {
                val rtt = clock() - ts
                // Same sanity rule as the other ping paths: replies that
                // cannot be tied to one of our recent pings (clock skew,
                // bogus echo) are dropped instead of recorded as 0 ms.
                if (rtt in 0..60_000) {
                    recordPingRtt(rtt)
                }
                return
            }
        }

        val header = plain[0].toInt() and 0xff
        val type = (header ushr 5) and 0x07

        when (type) {
            UdpType.PING -> handlePing(plain)
            UdpType.VOICE_OPUS -> handleOpusAudio(plain)
            UdpType.VOICE_CELT_ALPHA, UdpType.VOICE_CELT_BETA, UdpType.VOICE_SPEEX ->
                // Obsolete codecs are no longer supported by the official client.
                Log.d(TAG, "Dropping legacy audio packet using obsolete codec type=$type")
            else ->
                Log.d(TAG, "Ignoring unknown UDP message type=$type")
        }
    }

    /**
     * Parses and plays an Opus audio packet.
     *
     * Legacy framing: `[header][senderSession varint][frameNumber varint][size varint][opus]`.
     * Protobuf framing: `[header][Audio protobuf]`.
     *
     * Each speaker gets its own decoder state; a shared decoder mixes speakers
     * and causes the "starts fine then always noisy" artifact.
     */
    private fun handleOpusAudio(plain: ByteArray) {
        if (protobufMode) {
            val audio = ProtoUdpCodec.decodeAudio(plain, 1, plain.size - 1) ?: return
            handleDecodedFrame(audio.session, audio.frameNumber, audio.payload, audio.isLastFrame)
        } else {
            val p = UdpPacketCodec.parseLegacyOpusFull(plain) ?: return
            handleDecodedFrame(p.session, p.frameNumber, p.payload, p.isLastFrame)
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
     * Decodes a plaintext UDPTunnel body. Tries protobuf then legacy so a
     * fallback session still plays when the negotiated framing disagrees
     * with the tunneled header (force-TCP only ever used [handleOpusAudio]).
     */
    fun playTunneled(body: ByteArray) {
        if (body.isEmpty()) return
        val header = body[0].toInt() and 0xff
        if (header == ProtoUdpCodec.HEADER_AUDIO) {
            ProtoUdpCodec.decodeAudio(body, 1, body.size - 1)?.let {
                handleDecodedFrame(it.session, it.frameNumber, it.payload, it.isLastFrame)
                return
            }
        }
        UdpPacketCodec.parseLegacyOpusFull(body)?.let {
            handleDecodedFrame(it.session, it.frameNumber, it.payload, it.isLastFrame)
        }
    }

    /**
     * Handles a legacy connectivity-ping reply: `[header][varint timestamp]`.
     * The server echoes the timestamp we sent, so the round-trip time is
     * `now - timestamp`.
     */
    private fun handlePing(plain: ByteArray) {
        val ts = UdpPacketCodec.readPingTimestamp(plain) ?: return
        val rtt = clock() - ts
        // Same sanity rule as the extended ping: replies that cannot be tied
        // to one of our recent pings (clock skew, bogus echo, ts <= 0) are
        // dropped instead of recorded as 0 ms samples.
        if (ts <= 0 || rtt !in 0..60_000) return
        recordPingRtt(rtt)
    }

    /**
     * Handles a header-less 24-byte extended legacy ping reply from the server:
     * `[4B server version (BE)][8B echoed timestamp][4B users][4B max users]
     * [4B max bandwidth]`. The timestamp is the raw 64-bit value the client
     * sent (written little-endian by us and copied verbatim by the server).
     */
    private fun handleExtendedPing(plain: ByteArray) {
        val ts = ServerPingCodec.decode(plain)?.timestamp ?: return
        val rtt = clock() - ts
        // A bogus timestamp or an out-of-range RTT means this reply cannot be
        // tied to one of our pings (malformed or spoofed). Dropping the sample
        // keeps the mean/variance clean; recording 0 would drag the average
        // down and inflate the variance. The official decoder matches this
        // semantics: packets that do not decode are silently discarded before
        // any statistics are updated (ServerHandler.cpp), and a sane reply
        // always echoes our own timestamp, so a valid RTT lands in range.
        if (ts <= 0 || rtt !in 0..60_000) return
        recordPingRtt(rtt)
    }

    /** Records a measured UDP RTT and notifies the listener (when present). */
    private fun recordPingRtt(rttMillis: Long) {
        synchronized(pingStatsLock) {
            udpPingSamples++
            udpPingTotal += rttMillis
            udpPingSumSq += rttMillis.toDouble() * rttMillis
        }
        listener?.onUdpPing(rttMillis)
    }

    /**
     * Plaintext connectivity-ping body (protobuf or legacy). Used encrypted
     * on UDP and as a UDPTunnel payload so murmur sets `aiUdpFlag = 0`.
     */
    fun plaintextPingBody(): ByteArray {
        val timestamp = clock()
        return if (protobufMode) {
            ProtoUdpCodec.encodePing(timestamp)
        } else {
            UdpPacketCodec.encodePing(timestamp)
        }
    }

    /**
     * Sends a UDP ping packet (OCB2-encrypted). Uses the protobuf Ping
     * framing for Mumble >= 1.5.0 servers and the legacy
     * `[header][varint timestamp]` framing otherwise.
     */
    private fun sendPing() {
        if (!crypt.isReady || !running.get()) return
        val packetData = crypt.encrypt(plaintextPingBody()) ?: return
        sendDatagram(packetData)
    }

    /**
     * Sends an Opus audio packet to the server (normal talking). The header
     * byte, frame-number/size fields (legacy) or protobuf Audio message
     * (protobuf) are encrypted together with the Opus payload.
     *
     * @param isLastFrame set on the final packet of an utterance
     *        (legacy: 0x2000 size-flag / protobuf: is_terminator), mirroring
     *        the official client's end-of-transmission marker.
     */
    @JvmOverloads
    fun sendAudio(pcm: ShortArray, isLastFrame: Boolean = false): Boolean {
        if (!crypt.isReady) return false
        val encoded = opus.encode(pcm) ?: return false
        return sendEncoded(encoded, isLastFrame, outgoingFrameCount(pcm.size))
    }

    /**
     * Encrypts and sends an already encoded Opus payload as a voice packet.
     * @return false when the datagram could not be written (caller should
     *         tunnel the same plaintext over TCP, like official `!bUdp`).
     */
    fun sendEncoded(
        payload: ByteArray,
        isLastFrame: Boolean = false,
        frameCount: Int = outgoingFrameCount(),
    ): Boolean {
        if (!crypt.isReady || socket == null) return false
        val packetData = crypt.encrypt(buildVoiceBody(payload, isLastFrame, frameCount)) ?: return false
        return sendDatagram(packetData)
    }

    /**
     * Encrypts a pre-built plaintext voice body and writes it to UDP.
     * Used by the unified send path so TCP fallback can reuse the same body
     * (and sequence number) without encoding twice.
     */
    fun sendPlaintextUdp(body: ByteArray): Boolean {
        if (!crypt.isReady || socket == null) return false
        val packetData = crypt.encrypt(body) ?: return false
        return sendDatagram(packetData)
    }

    /** Writes an already-encrypted datagram. UDP pings use this (`force` in official). */
    private fun sendDatagram(packetData: ByteArray): Boolean {
        val sock = socket ?: return false
        val dest = peerAddress ?: return false
        return try {
            sock.send(DatagramPacket(packetData, packetData.size, dest, peerPort))
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error", e)
            false
        }
    }

    /**
     * Encodes one frame of digital silence — the payload used by the official
     * client for its end-of-transmission packet.
     */
    fun encodeSilence(): ByteArray? = opus.encode(ShortArray(opus.getFrameSize()))

    /**
     * Builds the full plaintext voice packet body (header + payload in the
     * negotiated framing) used both as the OCB2-encrypted UDP datagram and as
     * the plaintext body of a force-TCP UDPTunnel message.
     */
    private fun buildVoiceBody(
        payload: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int = outgoingFrameCount(),
    ): ByteArray {
        val frameNumber = frameCounter.allocate(frameCount)
        return if (protobufMode) {
            ProtoUdpCodec.encodeAudio(frameNumber, payload, target = 0, isLastFrame = isLastFrame)
        } else {
            UdpPacketCodec.encodeLegacyOpus(payload, isLastFrame, frameNumber)
        }
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
    ): ByteArray = buildVoiceBody(payload, isLastFrame, frameCount)

    /** Decoded tunneled audio with terminator flag. */
    data class TunnelAudio(val session: Int, val payload: ByteArray, val isLastFrame: Boolean)

    /**
     * Decodes the plaintext body of a received UDPTunnel message into the
     * sender session and raw Opus payload (framing-aware).
     */
    fun decodeTunnelBody(body: ByteArray): Pair<Int, ByteArray>? {
        val t = decodeTunnelBodyFull(body) ?: return null
        return t.session to t.payload
    }

    fun decodeTunnelBodyFull(body: ByteArray): TunnelAudio? {
        return if (protobufMode) {
            if (body.isEmpty()) return null
            val audio = if (body[0].toInt() == ProtoUdpCodec.HEADER_AUDIO) {
                ProtoUdpCodec.decodeAudio(body, 1, body.size - 1)
            } else {
                ProtoUdpCodec.decodeAudio(body)
            }
            audio?.let {
                TunnelAudio(it.session, it.payload, it.isLastFrame)
            }
        } else {
            UdpPacketCodec.parseLegacyOpusFull(body)?.let {
                TunnelAudio(it.session, it.payload, it.isLastFrame)
            }
        }
    }

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
        crypt.reset()
        frameCounter.reset()
        lastGoodUdpMs = 0L
        cryptoReadyMs = 0L
        lastResyncRequestMs = 0L
        opus.close()
    }
}
