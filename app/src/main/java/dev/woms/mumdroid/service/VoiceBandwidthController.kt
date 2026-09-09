package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.net.UdpVoiceManager

/**
 * The bandwidth-adaptation state machine (official `AudioInput::adjustBandwidth`
 * glue): derives the effective Opus bitrate and packet duration from the user
 * settings and the server's `max_bandwidth`, applies them to the UDP manager,
 * and reports adjustments to the user at most once per (bitrate, frames) pair.
 */
internal class VoiceBandwidthController(
    /**
     * Notified when the server's cap forced a reduction, with
     * (server max kbit/s, effective kbit/s, effective packet duration ms).
     * Deduplicated: only fired when the (bitrate, frames) pair changes.
     */
    private val onBandwidthAdjusted: (serverMaxKbps: Int, adjustedKbps: Int, framesMs: Int) -> Unit,
) {
    /** Server `max_bandwidth` in bits/sec; 0 = not yet known. */
    var serverMaxBandwidthBps = 0
        private set

    /** Effective 10 ms frames per packet after adaptation. */
    var effectiveFramesPerPacket = 2
        private set

    /** Effective Opus bitrate in bits/sec after adaptation. */
    var effectiveBitrateBps = 40_000
        private set

    private var lastBandwidthNoticeBitrate = 0
    private var lastBandwidthNoticeFrames = 0

    /** Resets the adaptation state to the raw settings (connect / disconnect). */
    fun resetTo(settings: AppSettings) {
        effectiveFramesPerPacket = settings.framesPerPacket.coerceIn(1, 6)
        effectiveBitrateBps = settings.transmitQuality * 1000
        lastBandwidthNoticeBitrate = 0
        lastBandwidthNoticeFrames = 0
        serverMaxBandwidthBps = 0
    }

    /**
     * Applies the server's advertised `max_bandwidth`.
     * @return whether the value was accepted (positive); the caller should
     *         reconfigure afterwards.
     */
    fun onServerMaxBandwidth(maxBandwidth: Int): Boolean {
        if (maxBandwidth <= 0) return false
        serverMaxBandwidthBps = maxBandwidth
        return true
    }

    /**
     * Recomputes the effective bitrate/frames for [settings] under the current
     * server cap and applies them to [udp].
     *
     * @return whether the effective frames-per-packet changed (the caller
     *         must restart capture so the engine bundles the new duration).
     */
    fun reconfigure(settings: AppSettings, useTcp: Boolean, udp: UdpVoiceManager?): Boolean {
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
        udp?.let {
            it.bitrate = adjusted.bitrate
            it.framesPerPacket = adjusted.frames
            it.lowLatency = settings.lowLatency
            it.applyBitrate()
        }
        val wasAdjusted = serverMaxBandwidthBps > 0 &&
            (adjusted.bitrate != wantedBitrate || adjusted.frames != wantedFrames)
        if (wasAdjusted &&
            (adjusted.bitrate != lastBandwidthNoticeBitrate ||
                adjusted.frames != lastBandwidthNoticeFrames)
        ) {
            lastBandwidthNoticeBitrate = adjusted.bitrate
            lastBandwidthNoticeFrames = adjusted.frames
            onBandwidthAdjusted(
                serverMaxBandwidthBps / 1000,
                adjusted.bitrate / 1000,
                adjusted.frames * 10,
            )
        }
        return framesChanged
    }

    /** Wire bandwidth for the effective settings (official `getNetworkBandwidth`). */
    fun currentBandwidthBps(useTcp: Boolean): Int = VoiceBandwidth.getNetworkBandwidth(
        effectiveBitrateBps,
        effectiveFramesPerPacket,
        tcpMode = useTcp,
    )
}
