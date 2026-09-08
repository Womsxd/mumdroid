package dev.woms.mumdroid.core.audio

/**
 * Encoder application matching libopus `OPUS_APPLICATION_*` and Concentus
 * `OpusApplication`. Selected from bitrate + the user low-latency flag.
 */
internal enum class OpusApplicationMode {
    VOIP,
    AUDIO,
    LOW_DELAY,
}

/**
 * Encode/decode backend used by [OpusCodec]. Implementations own encoder
 * state and the per-session decoder map.
 */
internal interface OpusBackend {
    fun ensureEncoder(app: OpusApplicationMode, bitrate: Int)
    fun resetEncoder()
    fun encode(pcm: ShortArray, samples: Int): ByteArray?
    fun decode(session: Int, packet: ByteArray, isTerminator: Boolean): ShortArray?
    fun decodePlc(session: Int, frameSize: Int): ShortArray?
    fun resetDecoder(session: Int)
    fun close()
}
