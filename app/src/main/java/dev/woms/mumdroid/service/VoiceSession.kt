package dev.woms.mumdroid.service

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.AudioInput
import dev.woms.mumdroid.core.audio.AudioOutput
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.SelfMuteDeaf
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UdpVoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _selfMuted = MutableStateFlow(false)
    val selfMuted: StateFlow<Boolean> = _selfMuted

    private val _selfDeafened = MutableStateFlow(false)
    val selfDeafened: StateFlow<Boolean> = _selfDeafened

    private val _talking = MutableStateFlow(false)
    private val _vadLevel = MutableStateFlow(0)

    var udp: UdpVoiceManager? = null
    @Volatile
    private var protobufMode = false

    private var halfDuplex = false
    private var voiceMode = VoiceMode.CONTINUOUS
    @Volatile
    private var pttHeld = false
    private var audioInput: AudioInput? = null
    private var audioOutput: AudioOutput? = null
    private var unmuteOnUndeaf = false

    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    val serverMaxBandwidthBps: Int
        get() = bandwidth.serverMaxBandwidthBps

    val outputTarget: StateFlow<VoiceOutputTarget?>
        get() = routeController.outputTarget

    private val useTcp: Boolean
        get() = fallback.useTcp(callbacks.forceTcp())

    fun selfMutedValue(): Boolean = _selfMuted.value
    fun selfDeafenedValue(): Boolean = _selfDeafened.value

    fun applyInitialSettings(settings: AppSettings) {
        voiceMode = settings.voiceMode
        halfDuplex = settings.halfDuplex
        bandwidth.resetTo(settings)
        fallback.resetTo(settings.forceTcp)
    }

    fun resetEncodeToSettings(settings: AppSettings) {
        bandwidth.resetTo(settings)
    }

    fun start(session: Int) {
        routeController.resetOverride()
        routeController.applyOutputRoute()
        audioOutput = createAudioOutput(routeController.currentMediaUsage())
        attachVoicePlayback()
        if (!callbacks.forceTcp()) {
            maybeStartUdp()
        }
        startCapture()
    }

    fun stop() {
        transmitter.terminate()
        pttHeld = false
        audioInput?.stop()
        audioInput = null
        audioOutput?.stop()
        audioOutput = null
        _talking.value = false
        _vadLevel.value = 0
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
            audioInput?.applySettings(
                noiseEnabled = next.noiseSuppressionEnabled,
                mode = next.noiseSuppressionMode,
                suppressionDb = next.noiseSuppressionDb,
                agcMode = next.agcMode,
                agcEnabled = next.agcEnabled,
                agcMaxGainDb = next.agcMaxGainDb,
                inputVolume = next.inputVolume,
                aecMode = activeAecMode(),
                aecEnabled = next.aecEnabled,
                micSource = next.micSource,
                vadGating = next.voiceMode == VoiceMode.VAD,
                vadMethod = next.vadMethod,
                vadSpeechThreshold = next.vadSpeechThreshold,
                vadSilenceThreshold = next.vadSilenceThreshold,
                vadHoldFrames = next.vadHoldFrames,
            )
        }
        if (changedVoiceMode) {
            voiceMode = next.voiceMode
            handleVoiceModeChange()
        }
        if (changedCaptureSession && audioInput != null) {
            restartCapture()
        }
        audioOutput?.volume = next.outputVolume
        halfDuplex = next.halfDuplex
        if (changedRoute && audioOutput != null) {
            routeController.applyOutputRoute()
        }
        if (changedOpus) {
            udp?.setOpusImplementation(next.opusImplementation)
            audioOutput?.setOpusImplementation(next.opusImplementation)
        }
        if (changedQuality || changedOpus || udp != null) {
            reconfigureBandwidth(udp)
        }
        udp?.qualityOfService = next.qualityOfService
    }

    fun setOutputTarget(target: VoiceOutputTarget) {
        routeController.setOutputTarget(target)
    }

    fun toggleSelfMute(): Boolean {
        applySelfMuteDeaf(currentMuteDeaf().toggleMute())
        return _selfMuted.value
    }

    fun toggleSelfDeafen(): Boolean {
        applySelfMuteDeaf(currentMuteDeaf().toggleDeafen())
        return _selfDeafened.value
    }

    fun clearMuteDeafen() {
        _selfMuted.value = false
        _selfDeafened.value = false
        unmuteOnUndeaf = false
    }

    private fun currentMuteDeaf(): SelfMuteDeaf =
        SelfMuteDeaf(_selfMuted.value, _selfDeafened.value, unmuteOnUndeaf)

    private fun applySelfMuteDeaf(next: SelfMuteDeaf) {
        val wasMuted = _selfMuted.value
        val wasDeafened = _selfDeafened.value
        _selfMuted.value = next.muted
        _selfDeafened.value = next.deafened
        unmuteOnUndeaf = next.unmuteOnUndeaf
        if (next.muted && !wasMuted) {
            endTransmission()
        } else if (!next.muted && wasMuted && voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
        if (next.deafened && !wasDeafened) {
            audioOutput?.stop()
        } else if (!next.deafened && wasDeafened) {
            audioOutput?.start()
        }
    }

    fun applyLocalSpeakBlock(wasBlocked: Boolean, nowBlocked: Boolean) {
        if (nowBlocked) {
            endTransmission()
        } else if (wasBlocked && voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
    }

    fun startTalking() {
        if (isTransmitBlocked()) return
        pttHeld = true
        if (voiceMode == VoiceMode.PTT) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
    }

    fun stopTalking() {
        if (voiceMode != VoiceMode.PTT) return
        pttHeld = false
        endTransmission()
    }

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
                if (shouldSuppressIncoming()) return
                audioOutput?.writePacket(session, frameNumber, payload, isLastFrame)
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
        if (framesChanged && audioInput != null) {
            restartCapture()
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

    private fun startCapture() {
        if (audioInput != null) return
        if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
        audioInput = AudioInput().apply {
            applyCaptureSettingsTo(this)
            setPreferredDevice(routeController.playbackRouter.inputDevice)
            start(object : AudioInput.Sink {
                override fun onPcmFrame(pcm: ShortArray) {
                    if (shouldTransmit()) transmitter.sendVoice(pcm)
                }

                override fun onSpeechDetected(active: Boolean) {
                    if (isTransmitBlocked()) return
                    val wasTalking = _talking.value
                    if (voiceMode == VoiceMode.VAD && wasTalking != active) {
                        _talking.value = active
                        callbacks.setUserTalking(callbacks.localSession(), active)
                    }
                    if (voiceMode == VoiceMode.VAD && wasTalking && !active) {
                        transmitter.terminate()
                    }
                }

                override fun onVadLevel(level: Int) {
                    if (voiceMode == VoiceMode.VAD) {
                        _vadLevel.value = level
                    }
                }
            })
        }
    }

    private fun isTransmitBlocked(): Boolean {
        if (_selfMuted.value || _selfDeafened.value) return true
        return callbacks.isServerSpeakBlocked()
    }

    private fun shouldTransmit(): Boolean {
        if (isTransmitBlocked()) return false
        if (voiceMode == VoiceMode.PTT) return pttHeld
        return true
    }

    private fun endTransmission() {
        if (_talking.value) {
            transmitter.terminate()
        }
        if (voiceMode == VoiceMode.PTT) pttHeld = false
        _talking.value = false
        _vadLevel.value = 0
        callbacks.setUserTalking(callbacks.localSession(), false)
    }

    private fun applyCaptureSettingsTo(input: AudioInput) {
        val settings = callbacks.settings()
        input.applySettings(
            noiseEnabled = settings.noiseSuppressionEnabled,
            mode = settings.noiseSuppressionMode,
            suppressionDb = settings.noiseSuppressionDb,
            agcMode = settings.agcMode,
            agcEnabled = settings.agcEnabled,
            agcMaxGainDb = settings.agcMaxGainDb,
            inputVolume = settings.inputVolume,
            aecMode = activeAecMode(),
            aecEnabled = settings.aecEnabled,
            micSource = settings.micSource,
            vadGating = voiceMode == VoiceMode.VAD,
            vadMethod = settings.vadMethod,
            vadSpeechThreshold = settings.vadSpeechThreshold,
            vadSilenceThreshold = settings.vadSilenceThreshold,
            vadHoldFrames = settings.vadHoldFrames,
            framesPerPacket = bandwidth.effectiveFramesPerPacket,
        )
    }

    private fun restartCapture() {
        audioInput?.stop()
        audioInput = null
        startCapture()
    }

    private fun handleVoiceModeChange() {
        audioInput?.let { applyCaptureSettingsTo(it) }
        if (voiceMode == VoiceMode.PTT) {
            if (!pttHeld) endTransmission()
        } else {
            pttHeld = false
            if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
                _talking.value = true
                callbacks.setUserTalking(callbacks.localSession(), true)
            }
        }
    }

    private fun createAudioOutput(media: Boolean): AudioOutput {
        val settings = callbacks.settings()
        return AudioOutput(
            mediaUsage = media,
            opusImplementation = settings.opusImplementation,
        ).apply {
            volume = settings.outputVolume
            echoReferenceTap = { pcm -> audioInput?.pushFarEndFrame(pcm) }
            speakerIdleTap = { session -> callbacks.setUserTalking(session, false) }
            speakerTalkingTap = { session, talking ->
                if (session != callbacks.localSession()) callbacks.setUserTalking(session, talking)
            }
            setPreferredDevice(routeController.playbackRouter.outputDevice)
            start()
        }
    }

    private fun activeAecMode(): AecMode {
        val target = routeController.currentTarget() ?: return callbacks.settings().aecMode
        return callbacks.settings().effectiveAecMode(target)
    }

    private fun shouldSuppressIncoming(): Boolean =
        halfDuplex && voiceMode != VoiceMode.CONTINUOUS && _talking.value

    // ---- VoiceRouteHost: live endpoints the route policy acts upon ----

    override fun settings(): AppSettings = callbacks.settings()

    override fun outputLive(): Boolean = audioOutput != null

    override fun inputLive(): Boolean = audioInput != null

    override fun onOutputMediaChanged() {
        audioOutput?.stop()
        audioOutput = createAudioOutput(routeController.currentMediaUsage())
        attachVoicePlayback()
    }

    override fun onAecChanged() {
        restartCapture()
    }

    override fun onInputDevice(device: AudioDeviceInfo?) {
        audioInput?.setPreferredDevice(device)
    }

    override fun onOutputDevice(device: AudioDeviceInfo?) {
        audioOutput?.setPreferredDevice(device)
    }
}
