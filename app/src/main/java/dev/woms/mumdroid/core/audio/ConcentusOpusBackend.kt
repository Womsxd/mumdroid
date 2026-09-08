package dev.woms.mumdroid.core.audio

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException

/**
 * Pure-Java Concentus backend. Encoder and per-session decoders live entirely
 * on the JVM; this is the default and the unit-test implementation.
 */
internal class ConcentusOpusBackend : OpusBackend {

    companion object {
        private const val TAG = "ConcentusOpus"
        private const val DECODER_TTL_MS = 30_000L
        private const val MAX_DECODERS = 32
    }

    private val encodeLock = Any()
    private var encoder: OpusEncoder
    private var currentApplication = OpusApplication.OPUS_APPLICATION_VOIP
    private var lastBitrate = 0
    private val outBuffer = ByteArray(OpusCodec.MAX_PACKET)

    private val decoders = java.util.concurrent.ConcurrentHashMap<Int, OpusDecoder>()
    private val decoderLastUse = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    init {
        try {
            encoder = OpusEncoder(
                OpusCodec.SAMPLE_RATE,
                OpusCodec.CHANNELS,
                OpusApplication.OPUS_APPLICATION_AUDIO,
            )
            encoder.setUseVBR(false)
            currentApplication = OpusApplication.OPUS_APPLICATION_AUDIO
        } catch (e: OpusException) {
            throw IllegalStateException("Failed to initialise Opus codec", e)
        }
    }

    override fun ensureEncoder(app: OpusApplicationMode, bitrate: Int) {
        synchronized(encodeLock) {
            lastBitrate = bitrate
            val concentusApp = app.toConcentus()
            if (concentusApp != currentApplication) {
                val newEncoder = OpusEncoder(
                    OpusCodec.SAMPLE_RATE,
                    OpusCodec.CHANNELS,
                    concentusApp,
                )
                if (bitrate > 0) newEncoder.setBitrate(bitrate)
                newEncoder.setUseVBR(false)
                encoder = newEncoder
                currentApplication = concentusApp
                return
            }
            if (bitrate > 0) encoder.setBitrate(bitrate)
            encoder.setUseVBR(false)
        }
    }

    override fun resetEncoder() {
        synchronized(encodeLock) {
            try {
                encoder.resetState()
            } catch (_: Exception) {
                try {
                    val newEncoder = OpusEncoder(
                        OpusCodec.SAMPLE_RATE,
                        OpusCodec.CHANNELS,
                        currentApplication,
                    )
                    if (lastBitrate > 0) newEncoder.setBitrate(lastBitrate)
                    newEncoder.setUseVBR(false)
                    encoder = newEncoder
                } catch (e: OpusException) {
                    Log.w(TAG, "Failed to reset Opus encoder", e)
                }
            }
        }
    }

    override fun encode(pcm: ShortArray, samples: Int): ByteArray? {
        synchronized(encodeLock) {
            return try {
                val len = encoder.encode(
                    pcm, 0, samples, outBuffer, 0, outBuffer.size,
                )
                if (len <= 0) null else outBuffer.copyOf(len)
            } catch (e: OpusException) {
                Log.w(TAG, "Opus encode failed", e)
                null
            }
        }
    }

    override fun decode(session: Int, packet: ByteArray, isTerminator: Boolean): ShortArray? {
        val dec = getOrCreateDecoder(session) ?: return null
        val out = ShortArray(OpusCodec.MAX_PACKET)
        return try {
            val len = synchronized(dec) {
                dec.decode(packet, 0, packet.size, out, 0, out.size, false)
            }
            if (isTerminator) {
                synchronized(dec) { try { dec.resetState() } catch (_: Exception) {} }
            }
            if (len <= 0) null else out.copyOf(len)
        } catch (e: OpusException) {
            Log.w(TAG, "Opus decode failed session=$session", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Opus decode failed session=$session", e)
            null
        }
    }

    override fun decodePlc(session: Int, frameSize: Int): ShortArray? {
        val dec = getOrCreateDecoder(session) ?: return null
        val out = ShortArray(frameSize.coerceAtMost(OpusCodec.MAX_PACKET))
        return try {
            val len = synchronized(dec) {
                try {
                    dec.decode(null, 0, 0, out, 0, out.size, false)
                } catch (_: Exception) {
                    -1
                }
            }
            if (len <= 0) null else out.copyOf(len)
        } catch (_: Exception) {
            null
        }
    }

    override fun resetDecoder(session: Int) {
        decoders[session]?.let { dec ->
            synchronized(dec) { try { dec.resetState() } catch (_: Exception) {} }
        }
    }

    override fun close() {
        decoders.clear()
        decoderLastUse.clear()
    }

    private fun reapIdleDecoders() {
        if (decoders.size <= MAX_DECODERS) {
            val now = System.currentTimeMillis()
            val it = decoderLastUse.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > DECODER_TTL_MS) {
                    decoders.remove(e.key)
                    it.remove()
                }
            }
        } else {
            val sorted = decoderLastUse.entries.sortedBy { it.value }
            for (i in 0 until sorted.size / 2) {
                decoders.remove(sorted[i].key)
                decoderLastUse.remove(sorted[i].key)
            }
        }
    }

    private fun getOrCreateDecoder(session: Int): OpusDecoder? {
        decoderLastUse[session] = System.currentTimeMillis()
        if ((decoderLastUse.size and 0x7F) == 0) reapIdleDecoders()
        return try {
            decoders.computeIfAbsent(session) {
                OpusDecoder(OpusCodec.SAMPLE_RATE, OpusCodec.CHANNELS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Opus decoder for session $session", e)
            decoderLastUse.remove(session)
            null
        }
    }

    private fun OpusApplicationMode.toConcentus(): OpusApplication = when (this) {
        OpusApplicationMode.VOIP -> OpusApplication.OPUS_APPLICATION_VOIP
        OpusApplicationMode.AUDIO -> OpusApplication.OPUS_APPLICATION_AUDIO
        OpusApplicationMode.LOW_DELAY ->
            OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY
    }
}
