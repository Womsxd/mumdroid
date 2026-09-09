package dev.woms.mumdroid.service

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UdpVoiceManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio endpoint orchestration for a connected session: capture and playback
 * lifecycles, PTT/VAD gating, self-mute/deafen, and the wiring between the
 * audio endpoints and the UDP/TCP voice transport.
 *
 * Focused collaborators own the individual policies:
 *  - [VoiceTransmitter] — outgoing frames: the hold-one-frame utterance
 *    state machine and the UDP → TCP-tunnel send fallback,
 *  - [VoiceBandwidthController] — bitrate/packet-size adaptation to the
 *    server's `max_bandwidth`,
 *  - [UdpFallbackController] — UDP availability state and the fallback /
 *    restore decisions,
 *  - [VoiceRouteController] — output device selection and the
 *    media/communication routing policy (this class implements
 *    [VoiceRouteHost] to supply the live audio endpoints).
 *
 * Threading: methods are entered from the TCP-reader thread, the ping
 * scheduler, `Dispatchers.Default` workers, the main thread and the
 * audio capture / UDP receive / playback threads. The original field
 * visibility semantics are preserved (@Volatile where it was before, no new
 * locks); StateFlow emission is thread-safe.
 */
internal class VoiceSession(
    audioManager: AudioManager,
    private val callbacks: Callbacks,
) : VoiceRouteHost {

    interface Callbacks {
        fun settings(): AppSettings
        fun client(): MumbleClient?
        fun localSession(): Int
        fun forceTcp(): Boolean
        fun setUserTalking(session: Int, talking: Boolean)
        fun isServerSpeakBlocked(): Boolean
        fun isLocallyBlocked(session: Int): Boolean
        fun appendSystemMessage(message: String)
        fun updateStatus(text: String)
        fun updateConnectedStatus()
        fun getString(id: Int): String
        fun getString(id: Int, vararg formatArgs: Any): String
    }

    private val routeController = VoiceRouteController(audioManager, host = this)

    private val bandwidth = VoiceBandwidthController(
        onBandwidthAdjusted = { serverMaxKbps, adjustedKbps, framesMs ->
            callbacks.appendSystemMessage(
                callbacks.getString(R.string.bandwidth_auto_adjusted, serverMaxKbps, adjustedKbps, framesMs),
            )
        },
    )

    private val fallback = UdpFallbackController()

    private val transmitter = VoiceTransmitter(
        channel = { udp },
        useTcp = { useTcp },
        sendTunneled = { body -> callbacks.client()?.sendTunneledVoice(body) },
    )

    private val muteDeaf = SelfMuteDeafController(
        onMuteChanged = { muted -> handleMuteChanged(muted) },
        onDeafenChanged = { deafened -> handleDeafenChanged(deafened) },
    )

    private val talk = TalkStateController(
        transmitter = transmitter,
        isTransmitBlocked = { isTransmitBlocked() },
        localSession = { callbacks.localSession() },
        setUserTalking = { session, talking -> callbacks.setUserTalking(session, talking) },
    )

    private val audioHost = object : VoiceAudioEndpoints.Host {
        override fun settings() = callbacks.settings()
        override fun localSession() = callbacks.localSession()
        override fun isLocallyBlocked(session: Int) = callbacks.isLocallyBlocked(session)
        override fun shouldSuppressIncoming() = talk.shouldSuppressIncoming()
        override fun shouldTransmit() = talk.shouldTransmit()
        override fun effectiveFramesPerPacket() = bandwidth.effectiveFramesPerPacket
        override fun vadGating() = talk.voiceMode == VoiceMode.VAD
        override fun onCaptureStarting() = talk.startContinuousTalking()
        override fun onPcmFrame(pcm: ShortArray) = transmitter.sendVoice(pcm)
        override fun onSpeechDetected(active: Boolean) = talk.onSpeechDetected(active)
        override fun onVadLevel(level: Int) = talk.onVadLevel(level)
        override fun setUserTalking(session: Int, talking: Boolean) =
            callbacks.setUserTalking(session, talking)
    }

    private val endpoints = VoiceAudioEndpoints(routeController, audioHost)

    val selfMuted: StateFlow<Boolean> get() = muteDeaf.muted
    val selfDeafened: StateFlow<Boolean> get() = muteDeaf.deafened

    var udp: UdpVoiceManager? = null
    @Volatile
    private var protobufMode = false

    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    val serverMaxBandwidthBps: Int
        get() = bandwidth.serverMaxBandwidthBps

    val outputTarget: StateFlow<VoiceOutputTarget?>
        get() = routeController.outputTarget

    private val useTcp: Boolean
        get() = fallback.useTcp(callbacks.forceTcp())

    fun selfMutedValue(): Boolean = muteDeaf.mutedValue
    fun selfDeafenedValue(): Boolean = muteDeaf.deafenedValue

    fun applyInitialSettings(settings: AppSettings) {
        talk.setVoiceMode(settings.voiceMode)
        talk.setHalfDuplex(settings.halfDuplex)
        bandwidth.resetTo(settings)
        fallback.resetTo(settings.forceTcp)
    }

    fun resetEncodeToSettings(settings: AppSettings) {
        bandwidth.resetTo(settings)
    }

    fun start(session: Int) {
        routeController.resetOverride()
        routeController.applyOutputRoute()
        endpoints.openOutput()
        attachVoicePlayback()
        if (!callbacks.forceTcp()) {
            maybeStartUdp()
        }
        endpoints.startCapture()
    }

    fun stop() {
        transmitter.terminate()
        endpoints.stop()
        talk.resetForStop()
    }

    fun closeTransport() {
        udp?.close()
        udp = null
    }

    fun leaveCall() {
        routeController.leaveCall()
    }

    fun applySettings(previous: AppSettings, next: AppSettings) {
        val changedNoise = next.noiseSuppressionEnabled != previous.noiseSuppressionEnabled ||
            next.noiseSuppressionMode != previous.noiseSuppressionMode ||
            next.noiseSuppressionDb != previous.noiseSuppressionDb ||
            next.agcEnabled != previous.agcEnabled ||
            next.agcMode != previous.agcMode ||
            next.agcMaxGainDb != previous.agcMaxGainDb ||
            next.aecEnabled != previous.aecEnabled ||
            next.aecMode != previous.aecMode ||
            next.vadMethod != previous.vadMethod ||
            next.vadSpeechThreshold != previous.vadSpeechThreshold ||
            next.vadSilenceThreshold != previous.vadSilenceThreshold ||
            next.vadHoldFrames != previous.vadHoldFrames ||
            next.inputVolume != previous.inputVolume ||
            next.micSource != previous.micSource
        val changedQuality = next.transmitQuality != previous.transmitQuality ||
            next.framesPerPacket != previous.framesPerPacket ||
            next.lowLatency != previous.lowLatency
        val changedOpus = next.opusImplementation != previous.opusImplementation
        val changedCaptureSession = next.aecEnabled != previous.aecEnabled ||
            next.aecMode != previous.aecMode ||
            next.micSource != previous.micSource
        val changedVoiceMode = next.voiceMode != previous.voiceMode
        val changedRoute = next.outputDeviceOrder != previous.outputDeviceOrder ||
            next.voicePlaybackMode != previous.voicePlaybackMode ||
            next.micSource != previous.micSource
        if (changedNoise) {
            endpoints.applyNoiseSettings(next)
        }
        if (changedVoiceMode) {
            talk.setVoiceMode(next.voiceMode)
            endpoints.applyCaptureSettingsIfLive()
            talk.applyVoiceModeChange()
        }
        if (changedCaptureSession) {
            endpoints.restartCaptureIfLive()
        }
        endpoints.setVolume(next.outputVolume)
        talk.setHalfDuplex(next.halfDuplex)
        if (changedRoute && endpoints.outputLive) {
            routeController.applyOutputRoute()
        }
        if (changedOpus) {
            udp?.setOpusImplementation(next.opusImplementation)
            endpoints.setOpusImplementation(next.opusImplementation)
        }
        if (changedQuality || changedOpus || udp != null) {
            reconfigureBandwidth(udp)
        }
        udp?.qualityOfService = next.qualityOfService
    }

    fun setOutputTarget(target: VoiceOutputTarget) {
        routeController.setOutputTarget(target)
    }

    fun toggleSelfMute(): Boolean = muteDeaf.toggleMute()

    fun toggleSelfDeafen(): Boolean = muteDeaf.toggleDeafen()

    fun clearMuteDeafen() = muteDeaf.clear()

    private fun handleMuteChanged(muted: Boolean) {
        if (muted) {
            talk.endTransmission()
        } else if (talk.voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            talk.startContinuousTalking()
        }
    }

    private fun handleDeafenChanged(deafened: Boolean) {
        if (deafened) endpoints.pauseOutput() else endpoints.resumeOutput()
    }

    fun applyLocalSpeakBlock(wasBlocked: Boolean, nowBlocked: Boolean) {
        if (nowBlocked) {
            talk.endTransmission()
        } else if (wasBlocked) {
            talk.startContinuousTalking()
        }
    }

    fun startTalking() = talk.startTalking()

    fun stopTalking() = talk.stopTalking()

    private fun attachVoicePlayback() {
        val udpManager = udp ?: return
        udpManager.setListener(object : UdpVoiceManager.Listener {
            override fun onAudioPacket(
                session: Int,
                frameNumber: Long,
                payload: ByteArray,
                isLastFrame: Boolean,
            ) {
                if (callbacks.isLocallyBlocked(session)) return
                if (talk.shouldSuppressIncoming()) return
                endpoints.writePacket(session, frameNumber, payload, isLastFrame)
            }

            override fun onUdpPing(rttMillis: Long) {}

            override fun onUdpConnected() {
                fallback.markProbeStarted()
                callbacks.updateConnectedStatus()
            }

            override fun onUdpError(message: String) {
                callbacks.updateStatus(callbacks.getString(R.string.status_voice, message))
                markUdpUnavailable(callbacks.getString(R.string.udp_unavailable_send))
            }
        })
    }

    private fun maybeStartUdp() {
        val udpManager = udp ?: return
        udpManager.protobufMode = protobufMode
        udpManager.onRequestCryptResync = { callbacks.client()?.requestCryptResync() }
        reconfigureBandwidth(udpManager)
        udpManager.qualityOfService = callbacks.settings().qualityOfService
        attachVoicePlayback()
        if (udpManager.isRunning) return
        val client = callbacks.client()
        udpManager.start(
            bindAddress = client?.localAddress,
            remoteAddress = client?.remoteAddress,
        )
    }

    /**
     * Recomputes the effective bitrate/frames and applies them to the UDP
     * codec; a changed packet duration requires a capture restart so the
     * engine bundles the new frame count.
     */
    private fun reconfigureBandwidth(udpManager: UdpVoiceManager? = udp) {
        val framesChanged = bandwidth.reconfigure(callbacks.settings(), useTcp, udpManager)
        if (framesChanged) {
            endpoints.restartCaptureIfLive()
        }
    }

    private fun markUdpUnavailable(message: String) {
        if (!fallback.markUnavailable(callbacks.forceTcp())) return
        callbacks.appendSystemMessage(message)
        reconfigureBandwidth()
    }

    fun evaluateUdpAvailability(remoteGood: Int) {
        val udp = this.udp ?: return
        when (val decision = fallback.evaluate(callbacks.forceTcp(), remoteGood, udp.packetStats().good)) {
            is UdpFallbackController.Decision.Fallback -> markUdpUnavailable(
                callbacks.getString(
                    when (decision.reason) {
                        UdpFallbackController.Reason.BOTH_DOWN -> R.string.udp_unavailable_both
                        UdpFallbackController.Reason.SEND_BROKEN -> R.string.udp_unavailable_send
                        UdpFallbackController.Reason.RECEIVE_BROKEN -> R.string.udp_unavailable_receive
                    },
                ),
            )
            UdpFallbackController.Decision.Restore -> {
                callbacks.appendSystemMessage(callbacks.getString(R.string.udp_available_again))
                maybeStartUdp()
                reconfigureBandwidth(udp)
            }
            null -> Unit
        }
    }

    fun onCryptSetup(
        host: String,
        port: Int,
        key: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) {
        val udp = this.udp ?: UdpVoiceManager(
            host,
            port,
            callbacks.settings().opusImplementation,
        ).also {
            it.protobufMode = protobufMode
            this.udp = it
        }
        when {
            key.isNotEmpty() && clientNonce.isNotEmpty() && serverNonce.isNotEmpty() ->
                udp.setupCryptography(key, clientNonce, serverNonce)
            serverNonce.isNotEmpty() -> {
                // Official msgCryptSetup nonce branch: resync++ then setDecryptIV.
                udp.resyncDecryptIV(serverNonce)
                reconfigureBandwidth(udp)
            }
            else -> {
                val iv = udp.encryptIV()
                if (iv != null) {
                    callbacks.client()?.sendCryptClientNonce(iv)
                }
            }
        }
        reconfigureBandwidth(udp)
        attachVoicePlayback()
        if (!callbacks.forceTcp()) {
            maybeStartUdp()
        }
    }

    fun playTunneled(body: ByteArray) {
        udp?.playTunneled(body)
    }

    fun applyMaxBandwidth(maxBandwidth: Int) {
        if (bandwidth.onServerMaxBandwidth(maxBandwidth)) {
            udp?.let { reconfigureBandwidth(it) }
        }
    }

    fun onServerVersion(protobuf: Boolean) {
        protobufMode = protobuf
        udp?.protobufMode = protobuf
    }

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

    fun currentBandwidthBps(): Int = bandwidth.currentBandwidthBps(useTcp)

    fun udpFallback(forceTcp: Boolean, live: Boolean): Boolean =
        fallback.isFallbackActive(forceTcp, live)

    private fun isTransmitBlocked(): Boolean {
        if (muteDeaf.isBlocked) return true
        return callbacks.isServerSpeakBlocked()
    }

    // ---- VoiceRouteHost: live endpoints the route policy acts upon ----

    override fun settings(): AppSettings = callbacks.settings()

    override fun outputLive(): Boolean = endpoints.outputLive

    override fun inputLive(): Boolean = endpoints.inputLive

    override fun onOutputMediaChanged() {
        endpoints.onOutputMediaChanged()
        attachVoicePlayback()
    }

    override fun onAecChanged() {
        endpoints.onAecChanged()
    }

    override fun onInputDevice(device: AudioDeviceInfo?) {
        endpoints.onInputDevice(device)
    }

    override fun onOutputDevice(device: AudioDeviceInfo?) {
        endpoints.onOutputDevice(device)
    }
}
