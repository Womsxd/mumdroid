package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.net.UdpVoiceManager
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice state a session snapshot needs, without the audio endpoints.
 *
 * Implemented by [VoiceSession]; extracted so [SessionFacade] and
 * [buildServerConnectionInfo] can be unit-tested without an Android
 * `AudioManager` (the real session's route controller needs one).
 */
internal interface SessionVoiceStatus {
    /** Active audio self-test mode (off / local / server). */
    val loopbackMode: StateFlow<LoopbackMode>

    /** Active shout/whisper target, or a regular-speech status. */
    val voiceTarget: StateFlow<VoiceTargetStatus>

    val outputTarget: StateFlow<VoiceOutputTarget?>

    val selfMuted: StateFlow<Boolean>

    val selfDeafened: StateFlow<Boolean>

    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    val serverMaxBandwidthBps: Int

    /** The UDP voice channel, or null in force-TCP mode. */
    val udp: UdpVoiceManager?

    /** Effective transmit bandwidth of the current configuration. */
    fun currentBandwidthBps(): Int

    /** Whether voice is currently tunneled over TCP. */
    fun udpFallback(forceTcp: Boolean, live: Boolean): Boolean
}
