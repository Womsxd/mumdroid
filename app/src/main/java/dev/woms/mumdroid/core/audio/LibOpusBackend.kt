package dev.woms.mumdroid.core.audio

import android.util.Log

/**
 * Native libopus backend. Encoder and per-session decoders are `OpusEncoder*`
 * / `OpusDecoder*` pointers owned through [LibOpusNative].
 */
internal class LibOpusBackend : OpusBackend {

    companion object {
        private const val TAG = "LibOpus"
        private const val DECODER_TTL_MS = 30_000L
        private const val MAX_DECODERS = 32
    }

    private class NativeHandle(var ptr: Long)

    private val encodeLock = Any()
    private val decoderLock = Any()
    private val encoder = NativeHandle(0L)
    private var currentApp = OpusApplicationMode.AUDIO
    private var lastBitrate = 0
    private val outBuffer = ByteArray(OpusCodec.MAX_PACKET)

    private val decoders = java.util.concurrent.ConcurrentHashMap<Int, NativeHandle>()
    private val decoderLastUse = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    init {
        if (!LibOpusNative.isAvailable) {
            throw IllegalStateException("libopus.so is not available")
        }
        val ptr = LibOpusNative.encoderCreate(
            OpusCodec.SAMPLE_RATE,
            OpusCodec.CHANNELS,
            LibOpusNative.APPLICATION_AUDIO,
        )
        if (ptr == 0L) {
            throw IllegalStateException("Failed to create native Opus encoder")
        }
        encoder.ptr = ptr
        LibOpusNative.encoderSetVbr(ptr, false)
        currentApp = OpusApplicationMode.AUDIO
    }

    override fun ensureEncoder(app: OpusApplicationMode, bitrate: Int) {
        synchronized(encodeLock) {
            lastBitrate = bitrate
            if (app != currentApp) {
                recreateEncoderLocked(app, bitrate)
                return
            }
            applyBitrateLocked(bitrate)
        }
    }

    override fun resetEncoder() {
        synchronized(encodeLock) {
            val ptr = encoder.ptr
            if (ptr == 0L) return
            if (LibOpusNative.encoderReset(ptr) < 0) {
                recreateEncoderLocked(currentApp, lastBitrate)
            }
        }
    }

    override fun encode(pcm: ShortArray, samples: Int): ByteArray? {
        synchronized(encodeLock) {
            val ptr = encoder.ptr
            if (ptr == 0L) return null
            val len = LibOpusNative.encode(ptr, pcm, samples, outBuffer)
            if (len <= 0) {
                if (len < 0) Log.w(TAG, "Opus encode failed err=$len")
                return null
            }
            return outBuffer.copyOf(len)
        }
    }

    override fun decode(session: Int, packet: ByteArray, isTerminator: Boolean): ShortArray? {
        val dec = getOrCreateDecoder(session) ?: return null
        val out = ShortArray(OpusCodec.MAX_PACKET)
        return synchronized(dec) {
            if (dec.ptr == 0L) return null
            val len = LibOpusNative.decode(dec.ptr, packet, out, out.size, false)
            if (isTerminator && dec.ptr != 0L) {
                LibOpusNative.decoderReset(dec.ptr)
            }
            if (len <= 0) {
                if (len < 0) Log.w(TAG, "Opus decode failed session=$session err=$len")
                null
            } else {
                out.copyOf(len)
            }
        }
    }

    override fun decodePlc(session: Int, frameSize: Int): ShortArray? {
        val dec = getOrCreateDecoder(session) ?: return null
        val size = frameSize.coerceAtMost(OpusCodec.MAX_PACKET)
        val out = ShortArray(size)
        return synchronized(dec) {
            if (dec.ptr == 0L) return null
            val len = LibOpusNative.decode(dec.ptr, null, out, size, false)
            if (len <= 0) null else out.copyOf(len)
        }
    }

    override fun resetDecoder(session: Int) {
        decoders[session]?.let { dec ->
            synchronized(dec) {
                if (dec.ptr != 0L) LibOpusNative.decoderReset(dec.ptr)
            }
        }
    }

    override fun close() {
        synchronized(encodeLock) {
            destroyEncoderLocked()
        }
        synchronized(decoderLock) {
            val snapshot = decoders.values.toList()
            decoders.clear()
            decoderLastUse.clear()
            for (dec in snapshot) {
                destroyDecoder(dec)
            }
        }
    }

    private fun recreateEncoderLocked(app: OpusApplicationMode, bitrate: Int) {
        destroyEncoderLocked()
        val ptr = LibOpusNative.encoderCreate(
            OpusCodec.SAMPLE_RATE,
            OpusCodec.CHANNELS,
            app.toNative(),
        )
        if (ptr == 0L) {
            Log.w(TAG, "Failed to re-create native Opus encoder")
            return
        }
        encoder.ptr = ptr
        currentApp = app
        applyBitrateLocked(bitrate)
    }

    private fun applyBitrateLocked(bitrate: Int) {
        val ptr = encoder.ptr
        if (ptr == 0L) return
        if (bitrate > 0) LibOpusNative.encoderSetBitrate(ptr, bitrate)
        LibOpusNative.encoderSetVbr(ptr, false)
    }

    private fun destroyEncoderLocked() {
        val ptr = encoder.ptr
        if (ptr != 0L) {
            LibOpusNative.encoderDestroy(ptr)
            encoder.ptr = 0L
        }
    }

    private fun reapIdleDecoders() {
        if (decoders.size <= MAX_DECODERS) {
            val now = System.currentTimeMillis()
            val it = decoderLastUse.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > DECODER_TTL_MS) {
                    decoders.remove(e.key)?.let { destroyDecoder(it) }
                    it.remove()
                }
            }
        } else {
            val sorted = decoderLastUse.entries.sortedBy { it.value }
            for (i in 0 until sorted.size / 2) {
                decoders.remove(sorted[i].key)?.let { destroyDecoder(it) }
                decoderLastUse.remove(sorted[i].key)
            }
        }
    }

    private fun destroyDecoder(dec: NativeHandle) {
        synchronized(dec) {
            if (dec.ptr != 0L) {
                LibOpusNative.decoderDestroy(dec.ptr)
                dec.ptr = 0L
            }
        }
    }

    private fun getOrCreateDecoder(session: Int): NativeHandle? {
        synchronized(decoderLock) {
            decoderLastUse[session] = System.currentTimeMillis()
            if ((decoderLastUse.size and 0x7F) == 0) reapIdleDecoders()
            val existing = decoders[session]
            if (existing != null) return existing
            val ptr = LibOpusNative.decoderCreate(OpusCodec.SAMPLE_RATE, OpusCodec.CHANNELS)
            if (ptr == 0L) {
                Log.w(TAG, "Failed to create Opus decoder for session $session")
                decoderLastUse.remove(session)
                return null
            }
            val created = NativeHandle(ptr)
            decoders[session] = created
            return created
        }
    }

    private fun OpusApplicationMode.toNative(): Int = when (this) {
        OpusApplicationMode.VOIP -> LibOpusNative.APPLICATION_VOIP
        OpusApplicationMode.AUDIO -> LibOpusNative.APPLICATION_AUDIO
        OpusApplicationMode.LOW_DELAY -> LibOpusNative.APPLICATION_LOWDELAY
    }
}
