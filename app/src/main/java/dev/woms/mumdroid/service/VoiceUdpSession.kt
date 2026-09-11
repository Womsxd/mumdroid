package dev.woms.mumdroid.service

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UdpVoiceManager

/**
 * Owns the UDP voice transport of a session: the [UdpVoiceManager] instance, the
 * negotiated framing, the playback listener wiring, the UDP→TCP fallback state,
 * and the bandwidth application to the UDP codec.
 *
 * Split out of [VoiceSession] so that class keeps capture/playback and talk
 * state while everything about the datagram channel lives here (the review's
 * "VoiceSession still holds 20+ state fields").
 */
internal class VoiceUdpSession(
    private val bandwidth: VoiceBandwidthController,
    private val fallback: UdpFallbackController,
    private val host: Host,
) {
    /** The parts of the owning session this collaborator acts upon. */
    interface Host {
        fun settings(): AppSettings
        fun forceTcp(): Boolean
        fun client(): MumbleClient?
        /** Another user's audio arrived and must be queued for playback. */
        fun onAudioPacket(
            session: Int,
            frameNumber: Long,
            payload: ByteArray,
            isLastFrame: Boolean,
            context: AudioContext,
        )
        /** A changed packet duration requires a capture restart. */
        fun onFramesPerPacketChanged()
        fun updateConnectedStatus()
        fun updateStatus(text: String)
        fun appendSystemMessage(message: String)
        fun getString(id: Int): String
        fun getString(id: Int, vararg formatArgs: Any): String
    }

    /** The UDP voice channel, or null in force-TCP mode / before CryptSetup. */
    var udp: UdpVoiceManager? = null
        private set

    @Volatile
    private var protobufMode = false

    /** Whether voice is currently tunneled over TCP (force-TCP or fallback). */
    val useTcp: Boolean get() = fallback.useTcp(host.forceTcp())

    fun resetFallback(forceTcp: Boolean) = fallback.resetTo(forceTcp)

    /** Whether the session is in UDP→TCP fallback. */
    fun isFallbackActive(forceTcp: Boolean, live: Boolean): Boolean =
        fallback.isFallbackActive(forceTcp, live)

    /** Recomputes the effective bitrate/frames and applies them to the UDP codec. */
    fun reconfigure(listener: UdpVoiceManager? = udp) {
        val framesChanged = bandwidth.reconfigure(host.settings(), useTcp, listener)
        if (framesChanged) host.onFramesPerPacketChanged()
    }

    /** Re-applies the codec bandwidth after the server reported its cap. */
    fun applyMaxBandwidth(maxBandwidth: Int) {
        if (bandwidth.onServerMaxBandwidth(maxBandwidth)) {
            udp?.let { reconfigure(it) }
        }
    }

    /** Records the negotiated framing (from the server's Version message). */
    fun onServerVersion(protobuf: Boolean) {
        protobufMode = protobuf
        udp?.protobufMode = protobuf
    }

    /**
     * Opens the UDP channel if the negotiated mode allows it. Idempotent while
     * the socket is already running.
     */
    fun startIfAllowed() {
        if (host.forceTcp()) return
        start()
    }

    private fun start() {
        val manager = udp ?: return
        manager.protobufMode = protobufMode
        manager.onRequestCryptResync = { host.client()?.requestCryptResync() }
        reconfigure(manager)
        manager.qualityOfService = host.settings().qualityOfService
        attachPlayback()
        if (manager.isRunning) return
        val client = host.client()
        manager.start(
            bindAddress = client?.localAddress,
            remoteAddress = client?.remoteAddress,
        )
    }

    /** (Re)binds the playback listener; safe to call before the socket opens. */
    fun attachPlayback() {
        val manager = udp ?: return
        manager.setListener(object : UdpVoiceManager.Listener {
            override fun onAudioPacket(
                session: Int,
                frameNumber: Long,
                payload: ByteArray,
                isLastFrame: Boolean,
                context: AudioContext,
            ) = host.onAudioPacket(session, frameNumber, payload, isLastFrame, context)

            override fun onUdpPing(rttMillis: Long) {}

            override fun onUdpConnected() {
                fallback.markProbeStarted()
                host.updateConnectedStatus()
            }

            override fun onUdpError(message: String) {
                host.updateStatus(host.getString(R.string.status_voice, message))
                markUnavailable(host.getString(R.string.udp_unavailable_send))
            }
        })
    }

    /**
     * Applies a CryptSetup: adopts the key/nonces on first delivery, resyncs the
     * decrypt IV on a nonce-only message, or answers with our own IV.
     */
    fun onCryptSetup(
        hostName: String,
        port: Int,
        key: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) {
        val manager = udp ?: UdpVoiceManager(
            hostName,
            port,
            host.settings().opusImplementation,
        ).also {
            it.protobufMode = protobufMode
            udp = it
        }
        when {
            key.isNotEmpty() && clientNonce.isNotEmpty() && serverNonce.isNotEmpty() ->
                manager.setupCryptography(key, clientNonce, serverNonce)
            serverNonce.isNotEmpty() -> {
                // Official msgCryptSetup nonce branch: resync++ then setDecryptIV.
                manager.resyncDecryptIV(serverNonce)
                reconfigure(manager)
            }
            else -> {
                val iv = manager.encryptIV()
                if (iv != null) {
                    host.client()?.sendCryptClientNonce(iv)
                }
            }
        }
        reconfigure(manager)
        attachPlayback()
        startIfAllowed()
    }

    /** Plays a plaintext voice body tunneled over TCP. */
    fun playTunneled(body: ByteArray) {
        udp?.playTunneled(body)
    }

    /**
     * Reacts to the server-reported count of good packets it received from us.
     * A sustained zero means our UDP send path is dead, so fall back to TCP.
     */
    fun onRemotePacketStats(remoteGood: Int) {
        val manager = udp ?: return
        when (val decision = fallback.evaluate(host.forceTcp(), remoteGood, manager.packetStats().good)) {
            is UdpFallbackController.Decision.Fallback -> markUnavailable(
                host.getString(
                    when (decision.reason) {
                        UdpFallbackController.Reason.BOTH_DOWN -> R.string.udp_unavailable_both
                        UdpFallbackController.Reason.SEND_BROKEN -> R.string.udp_unavailable_send
                        UdpFallbackController.Reason.RECEIVE_BROKEN -> R.string.udp_unavailable_receive
                    },
                ),
            )
            UdpFallbackController.Decision.Restore -> {
                host.appendSystemMessage(host.getString(R.string.udp_available_again))
                start()
                reconfigure(manager)
            }
            null -> Unit
        }
    }

    private fun markUnavailable(message: String) {
        if (!fallback.markUnavailable(host.forceTcp())) return
        host.appendSystemMessage(message)
        reconfigure()
    }

    /** Crypt/UDP statistics reported in the TCP Ping. */
    fun connectionStats(): MumbleClient.ConnectionStats {
        val stats = udp?.packetStats()
        return MumbleClient.ConnectionStats(
            good = stats?.good ?: 0,
            late = stats?.late ?: 0,
            lost = stats?.lost ?: 0,
            resync = stats?.resync ?: 0,
            udpPingAvg = (udp?.averageUdpPing ?: 0L).toFloat(),
            udpPingVar = udp?.udpPingVariance ?: 0f,
            udpPingPackets = udp?.udpPingCount ?: 0,
            tcpPingAvg = 0f,
            tcpPingVar = 0f,
            tcpPingPackets = 0,
        )
    }

    /** Full teardown: drops the socket, codec and OCB2 state. */
    fun close() {
        udp?.close()
        udp = null
    }

    /** Applies the configured Opus backend to the UDP codec, if one exists. */
    fun applyOpusImplementation(implementation: dev.woms.mumdroid.core.audio.OpusImplementation) {
        udp?.setOpusImplementation(implementation)
    }

    /** Applies the QoS flag to a live socket. */
    fun applyQualityOfService(enabled: Boolean) {
        udp?.qualityOfService = enabled
    }
}
