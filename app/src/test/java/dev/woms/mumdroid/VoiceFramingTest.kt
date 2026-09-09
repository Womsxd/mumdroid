package dev.woms.mumdroid

import com.google.protobuf.ByteString
import dev.woms.mumdroid.core.net.ProtoUdpCodec
import dev.woms.mumdroid.core.net.UdpPacketCodec
import dev.woms.mumdroid.core.net.UdpType
import dev.woms.mumdroid.core.net.VoiceFraming
import dev.woms.mumdroid.core.udpproto.Audio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceFramingTest {

    // Fixed monotonic clock: ping bodies carry the timestamp echoed by decode.
    private var nowMs = 50_000L

    private fun framing(protobufMode: Boolean): VoiceFraming =
        VoiceFraming(clock = { nowMs }).apply { this.protobufMode = protobufMode }

    /** Server→client legacy Opus body: `[header][session][frameNumber][size][opus]`. */
    private fun legacyServerAudio(
        session: Int,
        frameNumber: Long,
        payload: ByteArray,
        isLastFrame: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((UdpType.VOICE_OPUS shl 5) or 0)
        UdpPacketCodec.writeVarInt(session.toLong(), out)
        UdpPacketCodec.writeVarInt(frameNumber, out)
        UdpPacketCodec.writeVarInt((payload.size or if (isLastFrame) 0x2000 else 0).toLong(), out)
        out.write(payload)
        return out.toByteArray()
    }

    /** Server→client protobuf Audio body: `[HEADER_AUDIO][MumbleUDP.Audio]`. */
    private fun protobufAudioBody(
        session: Int,
        frameNumber: Long,
        payload: ByteArray,
        isLastFrame: Boolean,
    ): ByteArray {
        val audio = Audio.newBuilder()
            .setContext(0)
            .setSenderSession(session)
            .setFrameNumber(frameNumber)
            .setOpusData(ByteString.copyFrom(payload))
            .setIsTerminator(isLastFrame)
            .build()
        val proto = audio.toByteArray()
        return ByteArray(1 + proto.size).also {
            it[0] = ProtoUdpCodec.HEADER_AUDIO.toByte()
            System.arraycopy(proto, 0, it, 1, proto.size)
        }
    }

    /** 24-byte legacy extended ping reply; see `ServerPingCodec` for the layout. */
    private fun extendedPingReply(timestamp: Long): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x010500)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(timestamp)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8)
        buf.putInt(40)
        buf.putInt(72000)
        return buf.array()
    }

    @Test
    fun pingBody_legacyRoundTripsThroughDecodeDatagram() {
        val f = framing(protobufMode = false)
        val decoded = f.decodeDatagram(f.pingBody())
        assertTrue(decoded is VoiceFraming.Decoded.Ping)
        assertEquals(nowMs, (decoded as VoiceFraming.Decoded.Ping).timestamp)
    }

    @Test
    fun pingBody_protobufRoundTripsThroughDecodeDatagram() {
        val f = framing(protobufMode = true)
        val decoded = f.decodeDatagram(f.pingBody())
        assertTrue(decoded is VoiceFraming.Decoded.Ping)
        assertEquals(nowMs, (decoded as VoiceFraming.Decoded.Ping).timestamp)
    }

    @Test
    fun legacyServerAudio_decodesSessionFrameAndPayload() {
        val f = framing(protobufMode = false)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val decoded = f.decodeDatagram(
            legacyServerAudio(session = 42, frameNumber = 7, payload = payload, isLastFrame = true),
        ) as VoiceFraming.Decoded.Audio
        assertEquals(42, decoded.session)
        assertEquals(7L, decoded.frameNumber)
        assertArrayEquals(payload, decoded.payload)
        assertTrue(decoded.isLastFrame)
    }

    @Test
    fun legacyExtendedPing_routedByLengthBeforeProtobufSniff() {
        val f = framing(protobufMode = false)
        // First byte of the 24-byte reply is 0x00 (version major byte, BE) —
        // it must NOT be swallowed by the protobuf sniff (0x01) or the legacy
        // type dispatch; the extended ping is recognised by length.
        val decoded = f.decodeDatagram(extendedPingReply(timestamp = 99L))
        assertTrue(decoded is VoiceFraming.Decoded.Ping)
        assertEquals(99L, (decoded as VoiceFraming.Decoded.Ping).timestamp)
    }

    @Test
    fun legacyExtendedPing_24BytesShortCircuitRegardlessOfContent() {
        val f = framing(protobufMode = false)
        // Any 24-byte block routes to the extended-ping path (the timestamp is
        // read from bytes 4..12); it must never fall through to the legacy
        // type dispatch, whatever the surrounding fields contain.
        val weird = extendedPingReply(timestamp = 99L)
        weird[0] = 0x55 // corrupt the version bytes: content is irrelevant
        weird[2] = 0x77
        val decoded = f.decodeDatagram(weird)
        assertTrue(decoded is VoiceFraming.Decoded.Ping)
        assertEquals(99L, (decoded as VoiceFraming.Decoded.Ping).timestamp)
    }

    @Test
    fun protobufSniff_inLegacyMode_shortCircuits() {
        val f = framing(protobufMode = false)
        // [0x01][protobuf Ping] is a protobuf-framed ping even in legacy mode
        // (unversioned peers may already speak the new framing).
        val decoded = f.decodeDatagram(ProtoUdpCodec.encodePing(1234L))
        assertTrue(decoded is VoiceFraming.Decoded.Ping)
        assertEquals(1234L, (decoded as VoiceFraming.Decoded.Ping).timestamp)
    }

    @Test
    fun protobufSniffFailure_fallsThroughToLegacyDispatch() {
        val f = framing(protobufMode = false)
        // [0x01][garbage]: the sniff fails (no positive timestamp), so the
        // packet must fall through to the legacy type dispatch, where
        // header 0x01 → type 0 (CELT alpha, obsolete) → dropped.
        val decoded = f.decodeDatagram(byteArrayOf(0x01, 0x7f))
        assertEquals(VoiceFraming.Decoded.Unknown, decoded)
    }

    @Test
    fun obsoleteLegacyCodecs_decodeToUnknown() {
        val f = framing(protobufMode = false)
        assertEquals(
            VoiceFraming.Decoded.Unknown,
            f.decodeDatagram(byteArrayOf(((UdpType.VOICE_CELT_ALPHA shl 5)).toByte(), 1, 2)),
        )
        assertEquals(
            VoiceFraming.Decoded.Unknown,
            f.decodeDatagram(byteArrayOf(((UdpType.VOICE_SPEEX shl 5)).toByte(), 1, 2)),
        )
    }

    @Test
    fun unknownLegacyType_decodeToUnknown() {
        val f = framing(protobufMode = false)
        assertEquals(
            VoiceFraming.Decoded.Unknown,
            f.decodeDatagram(byteArrayOf(((5 shl 5)).toByte(), 1, 2)),
        )
    }

    @Test
    fun legacyBuild_stampsFrameNumbersAndLastFlag() {
        val f = framing(protobufMode = false)
        val payload = byteArrayOf(1, 2, 3)
        val p0 = f.buildVoiceBody(payload, isLastFrame = false, frameCount = 2)
        val p1 = f.buildVoiceBody(payload, isLastFrame = true, frameCount = 2)

        fun frameNumber(packet: ByteArray): Long {
            val parsed = UdpPacketCodec.readVarInt(packet, 1, packet.size)
            return parsed!!.first
        }

        assertEquals(UdpPacketCodec.talkHeaderByte(), p0[0])
        assertEquals(0L, frameNumber(p0))
        assertEquals(2L, frameNumber(p1))
        // isLastFrame rides the 0x2000 bit of the size varint.
        val frame = UdpPacketCodec.readVarInt(p1, 1, p1.size)!!
        val size = UdpPacketCodec.readVarInt(p1, frame.second, p1.size)!!
        assertEquals(0x2000L or payload.size.toLong(), size.first)
        assertArrayEquals(payload, p1.copyOfRange(size.second, p1.size))
    }

    @Test
    fun reset_restartsFrameNumbers() {
        val f = framing(protobufMode = false)
        f.buildVoiceBody(byteArrayOf(1), isLastFrame = false, frameCount = 2)
        f.reset()
        val body = f.buildVoiceBody(byteArrayOf(1), isLastFrame = false, frameCount = 2)
        assertEquals(0L, UdpPacketCodec.readVarInt(body, 1, body.size)!!.first)
    }

    @Test
    fun protobufAudio_buildThenDecodeRoundTrips() {
        val f = framing(protobufMode = true)
        val payload = byteArrayOf(9, 8, 7, 6)
        val decoded = f.decodeDatagram(
            f.buildVoiceBody(payload, isLastFrame = true, frameCount = 2),
        ) as VoiceFraming.Decoded.Audio
        // Client→server Audio has no sender session.
        assertEquals(0, decoded.session)
        assertEquals(0L, decoded.frameNumber)
        assertArrayEquals(payload, decoded.payload)
        assertTrue(decoded.isLastFrame)
        val next = f.decodeDatagram(
            f.buildVoiceBody(payload, isLastFrame = false, frameCount = 2),
        ) as VoiceFraming.Decoded.Audio
        assertEquals(2L, next.frameNumber)
        assertFalse(next.isLastFrame)
    }

    @Test
    fun decodeTunneled_isFramingLenient() {
        val payload = byteArrayOf(1, 2, 3, 4)
        // Protobuf body decoded while legacy mode is negotiated…
        val legacyMode = framing(protobufMode = false)
        val fromProtobuf = legacyMode.decodeTunneled(
            protobufAudioBody(session = 7, frameNumber = 3, payload = payload, isLastFrame = false),
        )
        assertEquals(7, fromProtobuf!!.session)
        assertEquals(3L, fromProtobuf.frameNumber)
        assertArrayEquals(payload, fromProtobuf.payload)
        // …and a legacy body decoded while protobuf mode is negotiated.
        val protobufMode = framing(protobufMode = true)
        val fromLegacy = protobufMode.decodeTunneled(
            legacyServerAudio(session = 9, frameNumber = 5, payload = payload, isLastFrame = true),
        )
        assertEquals(9, fromLegacy!!.session)
        assertEquals(5L, fromLegacy.frameNumber)
        assertTrue(fromLegacy.isLastFrame)
    }

    @Test
    fun decodeTunneled_rejectsGarbageAndEmpty() {
        val f = framing(protobufMode = false)
        assertNull(f.decodeTunneled(ByteArray(0)))
        assertNull(f.decodeTunneled(byteArrayOf(0x55, 0x01)))
    }
}
