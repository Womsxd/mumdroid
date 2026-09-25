package dev.woms.mumdroid.core.model

/**
 * The Opus encode surface the UDP voice send path drives.
 *
 * Declared in `core.model` — the only module both `core.net` (the consumer)
 * and `core.audio` (the implementation) may depend on — so the protocol layer
 * stops naming a concrete codec without introducing an `audio -> net` edge.
 */
interface VoiceEncoder {
    /**
     * Bundles [frames] 10 ms frames into each packet, mirroring the desktop
     * "Audio per packet" setting (2 = 20 ms).
     */
    fun setFramesPerPacket(frames: Int)

    /** Enables the low-latency Opus application mode (mirrors the desktop). */
    fun setLowLatency(enabled: Boolean)

    /** Sets the encode bitrate in bits-per-second (0 = codec default). */
    fun setBitrate(bitsPerSecond: Int)

    /** Switches the underlying Opus backend. */
    fun setImplementation(implementation: OpusImplementation)

    /** Encodes a PCM block into an Opus payload, or null on failure. */
    fun encode(pcm: ShortArray): ByteArray?

    /**
     * Encodes one packet of digital silence — the payload used by the official
     * client for its end-of-transmission packet.
     *
     * @return the Opus bytes and the 10 ms frame count actually consumed — not
     *         [setFramesPerPacket]'s value, which is not a legal Opus size at
     *         30/50 ms.
     */
    fun encodeSilence(): Pair<ByteArray, Int>?

    /** Official `OPUS_RESET_STATE` at the start of a talk spurt. */
    fun resetEncoder()

    /** Releases the encoder. */
    fun close()
}
