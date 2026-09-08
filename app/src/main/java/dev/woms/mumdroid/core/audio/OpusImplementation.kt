package dev.woms.mumdroid.core.audio

/**
 * Which Opus implementation to use for encode and decode.
 *
 *  - [CONCENTUS]: the pure-Java Concentus port (no native library).
 *  - [LIBOPUS]: native xiph/opus via JNI (the default). Falls back to
 *    [CONCENTUS] if the native library is missing.
 */
enum class OpusImplementation {
    CONCENTUS,
    LIBOPUS,
}
