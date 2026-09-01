package dev.woms.mumdroid.core.audio

/**
 * Server-bandwidth adaptation for the voice encoder, matching official
 * `AudioInput::getNetworkBandwidth` and `AudioInput::adjustBandwidth`.
 *
 * The user's `iQuality` / `iFramesPerPacket` are the requested values; the
 * server's `max_bandwidth` may force a larger packet (10→20 or 20→40 ms)
 * and then shave the bitrate down to 8 kbit/s so the IP+UDP+OCB2 overhead
 * still fits.
 */
object VoiceBandwidth {

    /** Official Audio Input quality slider minimum (`qsQuality` = 8000). */
    const val QUALITY_MIN_KBPS = 8

    /** Official Audio Input quality slider maximum (`qsQuality` = 192000). */
    const val QUALITY_MAX_KBPS = 192

    data class Adjusted(
        /** Opus encode bitrate in bits-per-second (`iAudioQuality`). */
        val bitrate: Int,
        /** 10 ms frames per packet (`iAudioFrames`: 1/2/4/6). */
        val frames: Int,
        /** Echo of the caller's low-delay flag (`bAllowLowDelay`). */
        val allowLowDelay: Boolean,
    )

    /**
     * Peak outgoing usage shown on the official quality page:
     * `AudioInputDialog::updateBitrate`.
     */
    data class PeakUsage(
        val audioBps: Int,
        val positionBps: Int,
        val overheadBps: Int,
    ) {
        val totalBps: Int get() = audioBps + positionBps + overheadBps
    }

    fun clampQualityKbps(kbps: Int): Int =
        kbps.coerceIn(QUALITY_MIN_KBPS, QUALITY_MAX_KBPS)

    /**
     * Audio / position / overhead breakdown at [frames] × 10 ms, matching
     * `AudioInputDialog::updateBitrate` (and therefore
     * `AudioInput::getNetworkBandwidth` when summed).
     *
     * Overhead bytes: 20 IP + 8 UDP + 4 OCB2 + 1 type + 2 framing +
     * [frames] + 12 when tunnelling over TCP. Position is 12 bytes when
     * whispering location. Packet rate is `100 / frames` per second, so
     * the byte cost is multiplied by `800 / frames`.
     */
    fun peakUsage(
        bitrate: Int,
        frames: Int,
        transmitPosition: Boolean = false,
        tcpMode: Boolean = false,
    ): PeakUsage {
        val f = frames.coerceAtLeast(1)
        val bitsPerBytePerSecond = 800 / f
        val overheadBytes = 20 + 8 + 4 + 1 + 2 + f + if (tcpMode) 12 else 0
        val positionBytes = if (transmitPosition) 12 else 0
        return PeakUsage(
            audioBps = bitrate,
            positionBps = positionBytes * bitsPerBytePerSecond,
            overheadBps = overheadBytes * bitsPerBytePerSecond,
        )
    }

    /**
     * Bits per second actually put on the wire for [bitrate] Opus plus
     * per-packet overhead at [frames] × 10 ms, matching
     * `AudioInput::getNetworkBandwidth`.
     */
    fun getNetworkBandwidth(
        bitrate: Int,
        frames: Int,
        transmitPosition: Boolean = false,
        tcpMode: Boolean = false,
    ): Int = peakUsage(bitrate, frames, transmitPosition, tcpMode).totalBps

    /**
     * Resolves the encode bitrate and packet size that fit [bitsPerSec].
     *
     * @param bitsPerSec server `max_bandwidth`, or `<= 0` / `-1` for no cap
     *        (official uses `-1`).
     * @param quality requested `iQuality` in bits-per-second
     * @param framesPerPacket requested `iFramesPerPacket`
     */
    fun adjustBandwidth(
        bitsPerSec: Int,
        quality: Int,
        framesPerPacket: Int,
        allowLowDelay: Boolean = false,
        transmitPosition: Boolean = false,
        tcpMode: Boolean = false,
    ): Adjusted {
        var frames = framesPerPacket.coerceIn(1, 6)
        var bitrate = quality
        if (bitsPerSec > 0) {
            if (getNetworkBandwidth(bitrate, frames, transmitPosition, tcpMode) > bitsPerSec) {
                if (frames <= 4 && bitsPerSec <= 32_000) {
                    frames = 4
                } else if (frames == 1 && bitsPerSec <= 64_000) {
                    frames = 2
                } else if (frames == 2 && bitsPerSec <= 48_000) {
                    frames = 4
                }
                if (getNetworkBandwidth(bitrate, frames, transmitPosition, tcpMode) > bitsPerSec) {
                    while (
                        bitrate > 8_000 &&
                        getNetworkBandwidth(bitrate, frames, transmitPosition, tcpMode) > bitsPerSec
                    ) {
                        bitrate -= 1_000
                    }
                }
            }
        }
        if (bitrate <= 8_000) bitrate = 8_000
        return Adjusted(bitrate = bitrate, frames = frames, allowLowDelay = allowLowDelay)
    }

    /**
     * Official encoder uses restricted low-delay only when the flag is on
     * **and** the (possibly adjusted) bitrate is at least 64 kbit/s.
     */
    fun useLowDelay(allowLowDelay: Boolean, bitrate: Int): Boolean =
        allowLowDelay && bitrate >= 64_000
}
