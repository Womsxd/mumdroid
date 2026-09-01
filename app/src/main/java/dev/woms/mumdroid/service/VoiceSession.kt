package dev.woms.mumdroid.service

import android.media.AudioManager
import android.os.SystemClock
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.AudioInput
import dev.woms.mumdroid.core.audio.AudioOutput
import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.audio.VoicePlaybackRouter
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UdpAvailability
import dev.woms.mumdroid.core.net.UdpVoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Capture, playback, PTT/VAD gating, and the UDP/TCP voice path.
 */
internal class VoiceSession(
    audioManager: AudioManager,
    private val callbacks: Callbacks,
) {

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

    val playbackRouter = VoicePlaybackRouter(
        audioManager,
        onDevicesChanged = {
            if (audioOutput != null) applyOutputRoute()
        },
        onInputDeviceChanged = { device ->
            audioInput?.setPreferredDevice(device)
        },
        onOutputDeviceChanged = { device ->
            audioOutput?.setPreferredDevice(device)
        },
    )

    private val _outputTarget = MutableStateFlow<VoiceOutputTarget?>(null)
    val outputTarget: StateFlow<VoiceOutputTarget?> = _outputTarget

    private val _selfMuted = MutableStateFlow(false)
    val selfMuted: StateFlow<Boolean> = _selfMuted

    private val _selfDeafened = MutableStateFlow(false)
    val selfDeafened: StateFlow<Boolean> = _selfDeafened

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking

    private val _vadLevel = MutableStateFlow(0)
    val vadLevel: StateFlow<Int> = _vadLevel

    var udp: UdpVoiceManager? = null
    @Volatile
    var protobufMode = false
    @Volatile
    var udpAvailable = true
    @Volatile
    var udpProbeStartMs = 0L
    var serverMaxBandwidthBps = 0
    var effectiveFramesPerPacket = 2
    var effectiveBitrateBps = 40_000

    private var sessionOutputOverride: VoiceOutputTarget? = null
    private var routedMedia = false
    private var routedAec: AecMode? = null
    private var halfDuplex = false
    private var voiceMode = VoiceMode.CONTINUOUS
    @Volatile
    private var pttHeld = false
    private var audioInput: AudioInput? = null
    private var audioOutput: AudioOutput? = null
    private var lastBandwidthNoticeBitrate = 0
    private var lastBandwidthNoticeFrames = 0
    private var pendingVoicePcm: ShortArray? = null
    private var pendingVoiceFrames = 0
    private var voiceUtteranceOpen = false

    private val useTcp: Boolean
        get() = callbacks.forceTcp() || !udpAvailable

    fun talkingValue(): Boolean = _talking.value
    fun selfMutedValue(): Boolean = _selfMuted.value
    fun selfDeafenedValue(): Boolean = _selfDeafened.value

    fun applyInitialSettings(settings: AppSettings) {
        voiceMode = settings.voiceMode
        halfDuplex = settings.halfDuplex
        effectiveFramesPerPacket = settings.framesPerPacket.coerceIn(1, 6)
        effectiveBitrateBps = settings.transmitQuality * 1000
        lastBandwidthNoticeBitrate = 0
        lastBandwidthNoticeFrames = 0
        udpAvailable = !settings.forceTcp
        udpProbeStartMs = 0L
        serverMaxBandwidthBps = 0
    }

    fun resetEncodeToSettings(settings: AppSettings) {
        effectiveFramesPerPacket = settings.framesPerPacket.coerceIn(1, 6)
        effectiveBitrateBps = settings.transmitQuality * 1000
        lastBandwidthNoticeBitrate = 0
        lastBandwidthNoticeFrames = 0
        serverMaxBandwidthBps = 0
    }

    fun start(session: Int) {
        sessionOutputOverride = null
        applyOutputRoute()
        audioOutput = createAudioOutput()
        attachVoicePlayback()
        if (!callbacks.forceTcp()) {
            maybeStartUdp()
        }
        startCapture()
    }

    fun stop() {
        flushPendingVoice(isLastFrame = true)
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
        playbackRouter.leaveCall()
        sessionOutputOverride = null
        _outputTarget.value = null
        routedMedia = false
        routedAec = null
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
            applyOutputRoute()
        }
        if (changedQuality || udp != null) {
            configureUdpCodec(udp)
        }
        udp?.qualityOfService = next.qualityOfService
    }

    fun setOutputTarget(target: VoiceOutputTarget) {
        sessionOutputOverride = target
        applyOutputRoute()
    }

    fun toggleSelfMute(): Boolean {
        val newMute = !_selfMuted.value
        _selfMuted.value = newMute
        if (newMute) {
            endTransmission()
        } else if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
        return newMute
    }

    fun toggleSelfDeafen(): Boolean {
        val newDeaf = !_selfDeafened.value
        _selfDeafened.value = newDeaf
        _selfMuted.value = newDeaf
        if (newDeaf) {
            endTransmission()
            audioOutput?.stop()
        } else {
            audioOutput?.start()
            if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
                _talking.value = true
                callbacks.setUserTalking(callbacks.localSession(), true)
            }
        }
        return newDeaf
    }

    fun clearMuteDeafen() {
        _selfMuted.value = false
        _selfDeafened.value = false
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

    fun attachVoicePlayback() {
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
                udpProbeStartMs = SystemClock.elapsedRealtime()
                callbacks.updateConnectedStatus()
            }

            override fun onUdpError(message: String) {
                callbacks.updateStatus(callbacks.getString(R.string.status_voice, message))
                markUdpUnavailable(callbacks.getString(R.string.udp_unavailable_send))
            }
        })
    }

    fun maybeStartUdp() {
        val udpManager = udp ?: return
        udpManager.setLocalSession(callbacks.localSession())
        udpManager.protobufMode = protobufMode
        udpManager.onRequestCryptResync = { callbacks.client()?.requestCryptResync() }
        configureUdpCodec(udpManager)
        udpManager.qualityOfService = callbacks.settings().qualityOfService
        attachVoicePlayback()
        if (udpManager.isRunning) return
        val client = callbacks.client()
        udpManager.start(
            bindAddress = client?.localAddress,
            remoteAddress = client?.remoteAddress,
        )
    }

    fun configureUdpCodec(udpManager: UdpVoiceManager? = udp) {
        val settings = callbacks.settings()
        val wantedBitrate = settings.transmitQuality * 1000
        val wantedFrames = settings.framesPerPacket.coerceIn(1, 6)
        val adjusted = VoiceBandwidth.adjustBandwidth(
            bitsPerSec = if (serverMaxBandwidthBps > 0) serverMaxBandwidthBps else -1,
            quality = wantedBitrate,
            framesPerPacket = wantedFrames,
            allowLowDelay = settings.lowLatency,
            tcpMode = useTcp,
        )
        val framesChanged = adjusted.frames != effectiveFramesPerPacket
        effectiveFramesPerPacket = adjusted.frames
        effectiveBitrateBps = adjusted.bitrate
        udpManager?.let {
            it.bitrate = adjusted.bitrate
            it.framesPerPacket = adjusted.frames
            it.lowLatency = settings.lowLatency
            it.applyBitrate()
        }
        if (framesChanged && audioInput != null) {
            restartCapture()
        }
        val wasAdjusted = serverMaxBandwidthBps > 0 &&
            (adjusted.bitrate != wantedBitrate || adjusted.frames != wantedFrames)
        if (wasAdjusted &&
            (adjusted.bitrate != lastBandwidthNoticeBitrate ||
                adjusted.frames != lastBandwidthNoticeFrames)
        ) {
            lastBandwidthNoticeBitrate = adjusted.bitrate
            lastBandwidthNoticeFrames = adjusted.frames
            callbacks.appendSystemMessage(
                callbacks.getString(
                    R.string.bandwidth_auto_adjusted,
                    serverMaxBandwidthBps / 1000,
                    adjusted.bitrate / 1000,
                    adjusted.frames * 10,
                ),
            )
        }
    }

    fun markUdpUnavailable(message: String) {
        if (callbacks.forceTcp() || !udpAvailable) return
        udpAvailable = false
        callbacks.appendSystemMessage(message)
        configureUdpCodec(udp)
    }

    fun evaluateUdpAvailability(remoteGood: Int) {
        val udp = udp ?: return
        val localGood = udp.packetStats().good
        if (UdpAvailability.shouldFallbackToTcp(
                udpAvailable = udpAvailable,
                forceTcp = callbacks.forceTcp(),
                udpProbeStartMs = udpProbeStartMs,
                nowMs = SystemClock.elapsedRealtime(),
                remoteGood = remoteGood,
                localGood = localGood,
            )
        ) {
            markUdpUnavailable(
                when {
                    remoteGood == 0 && localGood == 0 ->
                        callbacks.getString(R.string.udp_unavailable_both)
                    remoteGood == 0 ->
                        callbacks.getString(R.string.udp_unavailable_send)
                    else ->
                        callbacks.getString(R.string.udp_unavailable_receive)
                },
            )
        } else if (UdpAvailability.shouldRestoreUdp(udpAvailable, callbacks.forceTcp(), remoteGood, localGood)) {
            udpAvailable = true
            callbacks.appendSystemMessage(callbacks.getString(R.string.udp_available_again))
            maybeStartUdp()
            configureUdpCodec(udp)
        }
    }

    fun onCryptSetup(
        host: String,
        port: Int,
        key: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) {
        val udp = this.udp ?: UdpVoiceManager(host, port).also {
            it.protobufMode = protobufMode
            this.udp = it
        }
        when {
            key.isNotEmpty() && clientNonce.isNotEmpty() && serverNonce.isNotEmpty() ->
                udp.setupCryptography(key, clientNonce, serverNonce)
            serverNonce.isNotEmpty() -> {
                // Official msgCryptSetup nonce branch: resync++ then setDecryptIV.
                udp.resyncDecryptIV(serverNonce)
                configureUdpCodec(udp)
            }
            else -> {
                val iv = udp.encryptIV()
                if (iv != null) {
                    callbacks.client()?.sendCryptClientNonce(iv)
                }
            }
        }
        configureUdpCodec(udp)
        attachVoicePlayback()
        if (!callbacks.forceTcp()) {
            maybeStartUdp()
        }
    }

    fun playTunneled(body: ByteArray) {
        udp?.playTunneled(body)
    }

    fun applyMaxBandwidth(maxBandwidth: Int) {
        if (maxBandwidth <= 0) return
        serverMaxBandwidthBps = maxBandwidth
        udp?.let { configureUdpCodec(it) }
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

    fun currentBandwidthBps(): Int = VoiceBandwidth.getNetworkBandwidth(
        effectiveBitrateBps,
        effectiveFramesPerPacket,
        tcpMode = useTcp,
    )

    fun udpFallback(forceTcp: Boolean, live: Boolean): Boolean =
        live && !forceTcp && !udpAvailable

    private fun startCapture() {
        if (audioInput != null) return
        if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) {
            _talking.value = true
            callbacks.setUserTalking(callbacks.localSession(), true)
        }
        audioInput = AudioInput().apply {
            applyCaptureSettingsTo(this)
            setPreferredDevice(playbackRouter.inputDevice)
            start(object : AudioInput.Sink {
                override fun onPcmFrame(pcm: ShortArray) {
                    if (shouldTransmit()) sendVoice(pcm)
                }

                override fun onSpeechDetected(active: Boolean) {
                    if (isTransmitBlocked()) return
                    val wasTalking = _talking.value
                    if (voiceMode == VoiceMode.VAD && wasTalking != active) {
                        _talking.value = active
                        callbacks.setUserTalking(callbacks.localSession(), active)
                    }
                    if (voiceMode == VoiceMode.VAD && wasTalking && !active) {
                        sendTransmissionTerminator()
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
            sendTransmissionTerminator()
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
            framesPerPacket = effectiveFramesPerPacket,
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

    private fun createAudioOutput(): AudioOutput {
        val target = _outputTarget.value
        val settings = callbacks.settings()
        val media = target != null && settings.usesMediaPlayback(target)
        routedMedia = media
        return AudioOutput(mediaUsage = media).apply {
            volume = settings.outputVolume
            echoReferenceTap = { pcm -> audioInput?.pushFarEndFrame(pcm) }
            speakerIdleTap = { session -> callbacks.setUserTalking(session, false) }
            speakerTalkingTap = { session, talking ->
                if (session != callbacks.localSession()) callbacks.setUserTalking(session, talking)
            }
            setPreferredDevice(playbackRouter.outputDevice)
            start()
        }
    }

    private fun activeAecMode(): AecMode {
        val target = _outputTarget.value ?: return callbacks.settings().aecMode
        return callbacks.settings().effectiveAecMode(target)
    }

    private fun applyOutputRoute() {
        val settings = callbacks.settings()
        val types = playbackRouter.availableOutputTypes()
        val connected = VoiceRouteSelection.connectedTargets(types)
        val override = sessionOutputOverride
        if (override != null && override !in connected) {
            sessionOutputOverride = null
        }
        val wanted = sessionOutputOverride?.takeIf { it in connected }
            ?: VoiceRouteSelection.pick(settings.outputDeviceOrder, connected)
        _outputTarget.value = wanted
        if (wanted == null) return
        val media = settings.usesMediaPlayback(wanted)
        val aec = settings.effectiveAecMode(wanted)
        val mediaChanged = media != routedMedia
        playbackRouter.enterCall(wanted, media)
        routedMedia = media
        if (audioOutput != null) {
            if (mediaChanged) {
                audioOutput?.stop()
                audioOutput = createAudioOutput()
                attachVoicePlayback()
            } else {
                audioOutput?.setPreferredDevice(playbackRouter.outputDevice)
            }
        }
        if (audioInput != null) {
            if (routedAec != null && routedAec != aec) {
                restartCapture()
            } else {
                audioInput?.setPreferredDevice(playbackRouter.inputDevice)
            }
        }
        routedAec = aec
    }

    private fun shouldSuppressIncoming(): Boolean =
        halfDuplex && voiceMode != VoiceMode.CONTINUOUS && _talking.value

    private fun sendVoice(pcm: ShortArray, isLastFrame: Boolean = false) {
        if (!voiceUtteranceOpen) {
            udp?.resetEncoder()
            voiceUtteranceOpen = true
        }
        val held = pendingVoicePcm
        val heldFrames = pendingVoiceFrames
        pendingVoicePcm = pcm.copyOf()
        pendingVoiceFrames = OpusCodec.tenMsFrames(pcm.size).coerceAtLeast(1)
        if (held != null) {
            emitVoicePcm(held, isLastFrame = false, heldFrames)
        }
        if (isLastFrame) flushPendingVoice(isLastFrame = true)
    }

    private fun emitVoicePcm(pcm: ShortArray, isLastFrame: Boolean, frameCount: Int) {
        val udpManager = udp ?: return
        val encoded = udpManager.encodeOpus(pcm) ?: return
        sendEncodedVoice(encoded, isLastFrame, frameCount)
    }

    private fun flushPendingVoice(isLastFrame: Boolean) {
        val last = pendingVoicePcm
        val frames = pendingVoiceFrames
        pendingVoicePcm = null
        pendingVoiceFrames = 0
        voiceUtteranceOpen = false
        if (last != null) {
            emitVoicePcm(last, isLastFrame, frames)
        } else if (isLastFrame) {
            val udpManager = udp ?: return
            val encoded = udpManager.encodeSilence() ?: return
            sendEncodedVoice(encoded, isLastFrame = true)
        }
    }

    private fun sendEncodedVoice(
        encoded: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int = effectiveFramesPerPacket.coerceIn(1, 6),
    ) {
        val udpManager = udp ?: return
        val body = udpManager.buildTunnelPacket(encoded, isLastFrame, frameCount)
        if (useTcp) {
            callbacks.client()?.sendTunneledVoice(body)
            return
        }
        if (udpManager.isRunning && udpManager.isCryptoReady()) {
            udpManager.sendPlaintextUdp(body)
        }
    }

    private fun sendTransmissionTerminator() {
        flushPendingVoice(isLastFrame = true)
    }
}
