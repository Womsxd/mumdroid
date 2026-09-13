package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.ConcentusOpusBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end coverage of the pure-Java Opus backend, plus the session-decoder
 * cap that [DecoderPoolTest] pins down in isolation.
 */
class ConcentusOpusBackendTest {

    private fun tone(samples: Int): ShortArray =
        ShortArray(samples) { i ->
            (8_000.0 * sin(2.0 * PI * 440.0 * i / OpusSampleRate)).toInt().toShort()
        }

    @Test
    fun encodeThenDecode_roundTripsAFrame() {
        val backend = ConcentusOpusBackend()
        try {
            val packet = backend.encode(tone(960), 960)
            assertNotNull("encode produced no packet", packet)

            val decoded = backend.decode(7, packet!!, isTerminator = false)
            assertNotNull("decode produced no PCM", decoded)
            assertEquals(960, decoded!!.size)
        } finally {
            backend.close()
        }
    }

    @Test
    fun decodePlc_synthesisesAFrameWithoutAPacket() {
        val backend = ConcentusOpusBackend()
        try {
            // Prime the session with a real packet so its decoder has state.
            val packet = backend.encode(tone(960), 960)!!
            backend.decode(3, packet, isTerminator = false)

            val concealed = backend.decodePlc(3, 960)
            assertNotNull("PLC produced no PCM", concealed)
            assertEquals(960, concealed!!.size)
        } finally {
            backend.close()
        }
    }

    @Test
    fun resetDecoder_leavesTheSessionUsable() {
        val backend = ConcentusOpusBackend()
        try {
            val packet = backend.encode(tone(960), 960)!!
            backend.decode(42, packet, isTerminator = true)
            backend.resetDecoder(42)
            assertEquals(1, backend.decoderCount)

            assertNotNull(backend.decode(42, packet, isTerminator = false))
        } finally {
            backend.close()
        }
    }

    @Test
    fun decoderCount_staysBoundedAcrossManySessions() {
        val backend = ConcentusOpusBackend()
        try {
            for (session in 1..200) {
                backend.decodePlc(session, 960)
            }
            assertTrue(
                "leaked one decoder per session: ${backend.decoderCount}",
                backend.decoderCount <= DecoderPoolCap,
            )
        } finally {
            backend.close()
        }
    }

    @Test
    fun close_dropsEveryDecoder() {
        val backend = ConcentusOpusBackend()
        backend.decodePlc(1, 960)
        backend.decodePlc(2, 960)
        assertTrue(backend.decoderCount > 0)

        backend.close()

        assertEquals(0, backend.decoderCount)
    }

    private companion object {
        const val OpusSampleRate = 48_000.0

        /** Cap plus one sweep interval: the batched sweep may overshoot by that. */
        const val DecoderPoolCap = 48
    }
}
