package dev.woms.mumdroid.core.audio

import dev.woms.mumdroid.core.model.OpusImplementation
import dev.woms.mumdroid.core.model.VoiceEncoder

/**
 * Adapts [OpusCodec] to the [VoiceEncoder] port the UDP voice send path drives.
 *
 * The port lives in `core.model`, so `core.net` depends on the abstraction
 * while this adapter — and the codec it wraps — stay unaware of the protocol
 * layer.
 */
class OpusVoiceEncoder(
    implementation: OpusImplementation = OpusImplementation.LIBOPUS,
) : VoiceEncoder {

    private val codec = OpusCodec(implementation)

    override fun setFramesPerPacket(frames: Int) {
        codec.setFrameSize(OpusCodec.FRAME_SIZE_10MS * frames)
    }

    override fun setLowLatency(enabled: Boolean) = codec.setLowLatency(enabled)

    override fun setBitrate(bitsPerSecond: Int) = codec.setBitrate(bitsPerSecond)

    override fun setImplementation(implementation: OpusImplementation) =
        codec.setImplementation(implementation)

    override fun encode(pcm: ShortArray): ByteArray? = codec.encode(pcm)

    override fun encodeSilence(): Pair<ByteArray, Int>? {
        val pcm = ShortArray(codec.getFrameSize())
        val encoded = codec.encode(pcm) ?: return null
        return encoded to OpusCodec.encodedTenMsFrames(pcm.size).coerceAtLeast(1)
    }

    override fun resetEncoder() = codec.resetEncoder()

    override fun close() = codec.close()
}
