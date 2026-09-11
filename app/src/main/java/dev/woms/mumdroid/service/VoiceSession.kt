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
 *    [VoiceRouteHost] to supply the live audio endpoints),
 *  - [VoiceUdpSession] — the UDP transport, negotiated framing, playback
 *    listener wiring and UDP→TCP fallback state,
 *  - [VoiceSettingsDelta] — which settings groups actually changed.
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
) : VoiceRouteHost, SessionVoiceStatus {

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

    override val selfMuted: StateFlow<Boolean> get() = muteDeaf.muted
    override val selfDeafened: StateFlow<Boolean> get() = muteDeaf.deafened

    /** Active shout/whisper target, or a regular-speech status. */
    override val voiceTarget: StateFlow<VoiceTargetStatus> get() = targets.status

    /** Active audio self-test mode (off / local / server). */
    override val loopbackMode: StateFlow<LoopbackMode> get() = loopback.mode

    /**
     * The UDP transport, its framing and fallback state. Kept out of this class
     * so it holds capture/playback and talk state only.
     */
    private val udpSession = VoiceUdpSession(
        bandwidth = bandwidth,
        fallback = fallback,
        host = object : VoiceUdpSession.Host {
            override fun settings() = callbacks.settings()
            override fun forceTcp() = callbacks.forceTcp()
            override fun client() = callbacks.client()
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

            override fun onFramesPerPacketChanged() = endpoints.restartCaptureIfLive()
            override fun updateConnectedStatus() = callbacks.updateConnectedStatus()
            override fun updateStatus(text: String) = callbacks.updateStatus(text)
            override fun appendSystemMessage(message: String) = callbacks.appendSystemMessage(message)
            override fun getString(id: Int) = callbacks.getString(id)
            override fun getString(id: Int, vararg formatArgs: Any) =
                callbacks.getString(id, *formatArgs)
        },
    )

    /** The UDP voice channel, or null in force-TCP mode / before CryptSetup. */
    override val udp: UdpVoiceManager? get() = udpSession.udp

    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    override val serverMaxBandwidthBps: Int
        get() = bandwidth.serverMaxBandwidthBps

    override val outputTarget: StateFlow<VoiceOutputTarget?>
        get() = routeController.outputTarget

    private val useTcp: Boolean get() = udpSession.useTcp

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
        udpSession.attachPlayback()
        udpSession.startIfAllowed()
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
        udpSession.close()
    }

    fun leaveCall() {
        routeController.leaveCall()
    }

    fun applySettings(previous: AppSettings, next: AppSettings) {
        val changed = VoiceSettingsDelta.between(previous, next)
        if (changed.noise) {
            endpoints.applyNoiseSettings(next)
        }
        if (changed.voiceMode) {
            talk.setVoiceMode(next.voiceMode)
            endpoints.applyCaptureSettingsIfLive()
            talk.applyVoiceModeChange()
        }
        if (changed.captureSession) {
            endpoints.restartCaptureIfLive()
        }
        endpoints.setVolume(next.outputVolume)
        talk.setHalfDuplex(next.halfDuplex)
        if (changed.route && endpoints.outputLive) {
            routeController.applyOutputRoute()
        }
        if (changed.opus) {
            udpSession.applyOpusImplementation(next.opusImplementation)
            endpoints.setOpusImplementation(next.opusImplementation)
        }
        if (changed.quality || changed.opus || udp != null) {
            udpSession.reconfigure()
        }
        udpSession.applyQualityOfService(next.qualityOfService)
    }

    fun playTunneled(body: ByteArray) = udpSession.playTunneled(body)

    fun applyMaxBandwidth(maxBandwidth: Int) = udpSession.applyMaxBandwidth(maxBandwidth)

    fun onServerVersion(protobuf: Boolean) = udpSession.onServerVersion(protobuf)

    fun onCryptSetup(
        host: String,
        port: Int,
        key: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) = udpSession.onCryptSetup(host, port, key, clientNonce, serverNonce)

    fun connectionStats(): MumbleClient.ConnectionStats = udpSession.connectionStats()

    fun evaluateUdpAvailability(remoteGood: Int) = udpSession.onRemotePacketStats(remoteGood)

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

    override fun currentBandwidthBps(): Int = bandwidth.currentBandwidthBps(useTcp)

    override fun udpFallback(forceTcp: Boolean, live: Boolean): Boolean =
        udpSession.isFallbackActive(forceTcp, live)

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
        udpSession.attachPlayback()
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
