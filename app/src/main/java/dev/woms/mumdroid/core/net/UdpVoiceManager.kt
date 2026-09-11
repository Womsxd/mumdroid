package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.OpusImplementation
import dev.woms.mumdroid.core.crypto.UdpVoiceCrypto
import dev.woms.mumdroid.core.model.AudioContext
import java.net.InetAddress

/**
 * The send-side surface of the voice channel: encode helpers, plaintext body
 * assembly and the UDP datagram writer. Implemented by [UdpVoiceManager] so
 * callers (the service-layer send pipeline, unit tests) can stay decoupled
 * from the concrete manager.
 */
interface VoiceSendChannel {
    /** Encodes a PCM frame into an Opus payload, or null on failure. */
    fun encodeOpus(pcm: ShortArray): ByteArray?

    /** Official `OPUS_RESET_STATE` at the start of a talk spurt. */
    fun resetEncoder()

    /**
     * Encodes one frame of digital silence — the payload used by the official
     * client for its end-of-transmission packet.
     *
     * @return Opus bytes and the 10 ms frame count actually consumed (not the
     *         configured frames-per-packet, which is not a legal Opus size at
     *         30/50 ms).
     */
    fun encodeSilence(): Pair<ByteArray, Int>?

    /**
     * Builds the plaintext voice packet body for the negotiated framing
     * (also the UDPTunnel body), allocating the outgoing frame number.
     *
     * @param target voice-target id for this frame: 0 regular speech, 1..30 a
     *        registered shout/whisper target.
     * @return the body, or null when [target] cannot be encoded (out of the
     *         five-header-bit range) — the caller must then not send anything.
     */
    fun buildTunnelPacket(
        payload: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int,
        target: Int = 0,
    ): ByteArray?

    /** Whether the UDP datagram path is running. */
    val isRunning: Boolean

    /** @return whether the OCB2 crypto state is ready for the UDP send path. */
    fun isCryptoReady(): Boolean

    /**
     * Encrypts a pre-built plaintext voice body and writes it as a UDP
     * datagram.
     *
     * @return false when the datagram could not be written (caller should
     *         tunnel the same plaintext over TCP, like official `!bUdp`).
     */
    fun sendPlaintextUdp(body: ByteArray): Boolean
}

/**
 * Manages the UDP voice channel used to send and receive encrypted voice and
 * ping traffic, mirroring the official Mumble client's transport behaviour.
 *
 * The socket itself lives in [UdpVoiceTransport] (socket lifecycle, the
 * peer-filtered receive loop, the datagram write path); the protocol policy
 * lives in dedicated collaborators:
 *  - [VoiceFraming] — legacy/protobuf body build and decode (both protocol
 *    generations; see its KDoc for the framing layouts),
 *  - [UdpVoiceCrypto] — the OCB2 state plus the official 5-second
 *    decryption-failure resync rule,
 *  - [UdpPingTracker] — UDP round-trip-time statistics.
 *
 * This class keeps the codec, the OCB2 send buffer, the ping cadence and the
 * decode/record decisions.
 *
 * As in the official client, only Opus audio is decoded; the obsolete CELT /
 * Speex codecs are dropped. Pings are sent periodically to detect UDP
 * connectivity and measure round-trip time.
 */
class UdpVoiceManager(
    private val host: String,
    private val port: Int,
    opusImplementation: OpusImplementation = OpusImplementation.LIBOPUS,
) : VoiceSendChannel {
    companion object {
        private const val TAG = "UdpVoiceManager"

        /** Largest datagram we accept or emit (see [UdpVoiceTransport.MAX_PACKET]). */
        private const val MAX_PACKET = UdpVoiceTransport.MAX_PACKET

        // Mirror the official client's ping cadence: the desktop `iPingIntervalMsec`
        // defaults to 5000 ms. Sending every second only wastes bandwidth on the
        // voice channel, so we align with the official 5-second interval.
        private const val PING_INTERVAL_MS = 5_000L
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
            context: AudioContext,
        ) {}

        /** A UDP ping round-trip time measurement, in milliseconds. */
        fun onUdpPing(rttMillis: Long)

        /** UDP connectivity established. */
        fun onUdpConnected()

        /** UDP error. */
        fun onUdpError(message: String)
    }

    /** Monotonic source for ping timestamps and local timeouts (official `QElapsedTimer`). */
    private val clock = { SystemClock.elapsedRealtime() }

    private val crypto = UdpVoiceCrypto(clock)
    private val framing = VoiceFraming(clock)
    private val pingTracker = UdpPingTracker()
    private val pingCadence = UdpPingTracker.Cadence(PING_INTERVAL_MS)
    private val sendLock = Any()
    private val encryptPacket = ByteArray(MAX_PACKET)
    private val opus = OpusCodec(opusImplementation)

    /**
     * Datagram transport: socket lifecycle, the peer-filtered receive loop and
     * the write path. This class keeps the voice protocol policy only.
     */
    private val transport = UdpVoiceTransport(host, port)
    private val callbacks = TransportCallbacks()
    @Volatile
    private var listener: Listener? = null

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
    var qualityOfService: Boolean
        get() = transport.qualityOfService
        set(value) {
            transport.qualityOfService = value
        }

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
    override val isRunning: Boolean get() = transport.isRunning

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
    override fun isCryptoReady(): Boolean = crypto.isReady

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
    override fun encodeOpus(pcm: ShortArray): ByteArray? = opus.encode(pcm)

    /** Switches the Opus encode/decode backend and re-applies bitrate settings. */
    fun setOpusImplementation(implementation: OpusImplementation) {
        opus.setImplementation(implementation)
        applyBitrate()
    }

    /** Official `OPUS_RESET_STATE` at the start of a talk spurt. */
    override fun resetEncoder() = opus.resetEncoder()

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
        applyBitrate()
        transport.start(bindAddress, remoteAddress, callbacks)
    }

    /**
     * Receive-loop hooks. The loop itself lives in [UdpVoiceTransport]; this
     * side keeps the protocol work (ping cadence, OCB2 decrypt, framing decode).
     */
    private inner class TransportCallbacks : UdpVoiceTransport.Callbacks {
        override fun onDatagram(data: ByteArray, length: Int) = handlePacket(data, length)

        /** Pings are only sent once the loop is primed (see [maybeSendPing]). */
        override fun onTick() = maybeSendPing()

        override fun onConnected() {
            listener?.onUdpConnected()
        }

        override fun onError(message: String) {
            listener?.onUdpError(message)
        }
    }

    private fun maybeSendPing() {
        if (!crypto.isReady || !transport.isRunning) return
        if (pingCadence.due(clock())) sendPing()
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
                decoded.context,
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

    private fun handleDecodedFrame(
        session: Int,
        frameNumber: Long,
        payload: ByteArray,
        isLastFrame: Boolean,
        context: AudioContext,
    ) {
        // Do not decode or conceal here. Official AudioOutputSpeech puts the
        // encoded packet into the jitter buffer with
        // `timestamp = iFrameSize * frameNumber` and decodes in timestamp
        // order at playback. Receive-time PLC with a +1 increment treated
        // every official 20 ms packet (seq 0, 2, 4…) as a loss.
        //
        // Talk state (including shout/whisper) is NOT derived here either: like
        // the desktop client it follows playback liveness in the jitter buffer,
        // which is why the packet's context travels with it.
        listener?.onAudioPacket(session, frameNumber, payload, isLastFrame, context)
    }

    /**
     * Plays a plaintext UDPTunnel body (force-TCP fallback). The dispatch in
     * [VoiceFraming.decodeTunneled] is framing-lenient so a fallback session
     * still plays when the negotiated framing disagrees with the tunneled
     * header.
     */
    fun playTunneled(body: ByteArray) {
        val decoded = framing.decodeTunneled(body) ?: return
        handleDecodedFrame(
            decoded.session,
            decoded.frameNumber,
            decoded.payload,
            decoded.isLastFrame,
            decoded.context,
        )
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
        if (!crypto.isReady || !transport.isRunning) return
        encryptAndSend(framing.pingBody())
    }

    /**
     * Encrypts a pre-built plaintext voice body and writes it to UDP.
     * Used by the unified send path so TCP fallback can reuse the same body
     * (and sequence number) without encoding twice.
     */
    override fun sendPlaintextUdp(body: ByteArray): Boolean {
        if (!crypto.isReady || !transport.isRunning) return false
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
            return transport.send(encryptPacket, n)
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
    override fun encodeSilence(): Pair<ByteArray, Int>? {
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
    override fun buildTunnelPacket(
        payload: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int,
        target: Int,
    ): ByteArray? = framing.buildVoiceBody(payload, isLastFrame, frameCount, target)

    /**
     * Closes the datagram socket and ping loop but keeps crypto/codec so
     * voice can continue over TCP tunnel (force-TCP / UDP fallback).
     */
    fun stopDatagram() {
        // A later start() must prime the clock again, not fire immediately.
        pingCadence.reset()
        transport.close()
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
