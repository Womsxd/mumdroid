package dev.woms.mumdroid.service

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.AudioContext
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.model.VoiceTargetStatus
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
        /** Live roster, so the target controller can see who is still around. */
        fun roster(): SessionRoster
        fun setUserTalkState(session: Int, state: TalkState)
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

    private val targets = VoiceTargetController(callbacks.roster())

    private val loopback = AudioLoopback(
        localSession = { callbacks.localSession() },
        // Routed through a method instead of touching [endpoints] directly: the
        // lambda is part of this property's initializer, and referencing a
        // property declared further down would make the field types mutually
        // dependent.
        play = { session, pcm -> writeLocalLoopback(session, pcm) },
    )

    private val transmitter = VoiceTransmitter(
        channel = { udp },
        useTcp = { useTcp },
        sendTunneled = { body -> callbacks.client()?.sendTunneledVoice(body) },
        // The self-test target overrides the whisper/shout id (official
        // AudioInput::encodeAudioFrame), and in the local mode nothing is sent
        // at all — the caller plays those frames back instead.
        targetId = { loopback.outgoingTargetId(targets.sendTargetId) },
        withholdAudio = { loopback.current.isLocal },
    )

    private val muteDeaf = SelfMuteDeafController(
        onMuteChanged = { muted -> handleMuteChanged(muted) },
        onDeafenChanged = { deafened -> handleDeafenChanged(deafened) },
    )

    private val talk = TalkStateController(
        transmitter = transmitter,
        isTransmitBlocked = { isTransmitBlocked() },
        localSession = { callbacks.localSession() },
        localTalkState = { localTalkState() },
        setUserTalkState = { session, state -> callbacks.setUserTalkState(session, state) },
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
        override fun onPcmFrame(pcm: ShortArray) {
            // Local self-test: the frame stays on this device, so it is never
            // encoded or sent (official LoopUser branch).
            if (loopback.loopLocal(pcm)) return
            transmitter.sendVoice(pcm)
        }
        override fun onSpeechDetected(active: Boolean) = talk.onSpeechDetected(active)
        override fun onVadLevel(level: Int) = talk.onVadLevel(level)
        override fun setUserTalkState(session: Int, state: TalkState) =
            callbacks.setUserTalkState(session, state)
    }

    private val endpoints = VoiceAudioEndpoints(routeController, audioHost)

    val selfMuted: StateFlow<Boolean> get() = muteDeaf.muted
    val selfDeafened: StateFlow<Boolean> get() = muteDeaf.deafened

    /** Active shout/whisper target, or a regular-speech status. */
    val voiceTarget: StateFlow<VoiceTargetStatus> get() = targets.status

    /** Active audio self-test mode (off / local / server). */
    val loopbackMode: StateFlow<LoopbackMode> get() = loopback.mode

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

    /**
     * Wires the voice-target controller to the session's control channel. A
     * null client detaches the sink (nothing may write during a teardown).
     */
    fun attachTargetSender(client: MumbleClient?) {
        targets.send = client?.let { c -> { type, message -> c.sendMessage(type, message) } }
    }

    /** Sets (or clears) the shout/whisper target for the session. */
    fun setVoiceTarget(spec: VoiceTargetSpec?) = targets.setSpec(spec)

    /**
     * Switches the audio self-test mode. Changing it ends the utterance in
     * flight: its held frame was captured under the previous mode, so flushing
     * it afterwards could send a self-test frame to the channel (or lose a
     * channel frame to the self-test).
     */
    fun setLoopback(mode: LoopbackMode) {
        if (!loopback.set(mode)) return
        talk.endTransmission()
    }

    /**
     * Re-checks the active target against the live roster (a target user left,
     * a channel was removed): a target with no receivers left is reported as
     * unavailable and stops transmitting instead of falling back to broadcast.
     */
    fun refreshVoiceTarget() = targets.onRosterChanged()

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
        // Revoke the server-side registrations: murmur keeps a client's voice
        // targets until they are cleared, so leaving without a clear would
        // leave ids dangling on the server across reconnects.
        targets.clear()
        // The self-test is session-scoped: it must never outlive the session
        // (or a stale mode would silence the next connection).
        loopback.reset()
        endpoints.stop()
        talk.resetForStop()
    }

    fun closeTransport() {
        attachTargetSender(null)
        targets.reset()
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
                context: AudioContext,
            ) {
                if (callbacks.isLocallyBlocked(session)) return
                if (talk.shouldSuppressIncoming()) return
                endpoints.writePacket(session, frameNumber, payload, isLastFrame, context)
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

    /** Queues a locally looped-back capture frame for playback. */
    private fun writeLocalLoopback(session: Int, pcm: ShortArray) {
        endpoints.writeLocalPcm(session, pcm)
    }

    /**
     * Talk state to show for the local user while transmitting. A self-test is
     * plain talking: the whisper/shout id is overridden (server mode) or not
     * used at all (local mode), so no whisper/shout indicator may be shown.
     */
    private fun localTalkState(): TalkState? =
        if (loopback.current.isActive) TalkState.TALKING else targets.activeTalkState()

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
