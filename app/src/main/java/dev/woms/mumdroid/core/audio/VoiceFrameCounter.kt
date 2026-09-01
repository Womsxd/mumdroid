package dev.woms.mumdroid.core.audio

/**
 * Outgoing voice `frameNumber`, matching official `AudioInput::iFrameCounter`.
 *
 * Units are **10 ms frames** (`iFrameSize = SAMPLE_RATE / 100`), not packets.
 * A 20 ms Opus packet is stamped with the first 10 ms index it contains and
 * then advances the counter by 2, so the sequence is `0, 2, 4, …`.
 *
 * Official `iSilentFrames > 500` resets the counter after ~5 s of idle so a
 * later talk spurt does not look like a huge timestamp jump.
 */
class VoiceFrameCounter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val resetAfterMs: Long = 5_000L,
) {
    var value: Long = 0L
        private set

    private var lastSendMs = 0L

    /**
     * @return the `frameNumber` to stamp on this packet, then advance by
     *         [frameCount] (duration of the packet in 10 ms units).
     */
    fun allocate(frameCount: Int): Long {
        val now = clock()
        if (lastSendMs != 0L && now - lastSendMs > resetAfterMs) {
            value = 0L
        }
        lastSendMs = now
        val seq = value
        value += frameCount.coerceAtLeast(1).toLong()
        return seq
    }

    fun reset() {
        value = 0L
        lastSendMs = 0L
    }
}
