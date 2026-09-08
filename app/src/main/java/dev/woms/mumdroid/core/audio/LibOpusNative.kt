package dev.woms.mumdroid.core.audio

import android.util.Log
import dev.woms.mumdroid.core.audio.LibOpusNative.isAvailable

/**
 * JNI entry points for `libopus.so` (xiph/opus). Handles are opaque
 * `OpusEncoder*` / `OpusDecoder*` pointers. Do not call these unless
 * [isAvailable] is true.
 */
internal object LibOpusNative {
    private const val TAG = "LibOpusNative"

    /** libopus `OPUS_APPLICATION_VOIP`. */
    const val APPLICATION_VOIP = 2048

    /** libopus `OPUS_APPLICATION_AUDIO`. */
    const val APPLICATION_AUDIO = 2049

    /** libopus `OPUS_APPLICATION_RESTRICTED_LOWDELAY`. */
    const val APPLICATION_LOWDELAY = 2051

    val isAvailable: Boolean =
        runCatching { System.loadLibrary("opus") }
            .onFailure { Log.w(TAG, "libopus.so not loaded", it) }
            .isSuccess

    @JvmStatic
    external fun encoderCreate(sampleRate: Int, channels: Int, application: Int): Long

    @JvmStatic
    external fun encoderDestroy(handle: Long)

    @JvmStatic
    external fun encoderSetBitrate(handle: Long, bps: Int): Int

    @JvmStatic
    external fun encoderSetVbr(handle: Long, enable: Boolean): Int

    @JvmStatic
    external fun encoderReset(handle: Long): Int

    @JvmStatic
    external fun encode(handle: Long, pcm: ShortArray, samples: Int, out: ByteArray): Int

    @JvmStatic
    external fun decoderCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic
    external fun decoderDestroy(handle: Long)

    @JvmStatic
    external fun decoderReset(handle: Long): Int

    @JvmStatic
    external fun decode(
        handle: Long,
        packet: ByteArray?,
        pcm: ShortArray,
        frameSize: Int,
        fec: Boolean,
    ): Int
}
