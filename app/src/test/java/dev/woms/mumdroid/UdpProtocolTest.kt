package dev.woms.mumdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpProtocolTest {

    // ---- UdpPacketCodec (legacy UDP framing) ----

    @Test
    fun udpVarintRoundTrip() {
        val values = longArrayOf(
            0, 1, 127, 128, 300, 8191, 8192, 0x1FFF,
            Int.MAX_VALUE.toLong(), Long.MAX_VALUE,
            -1, -2, -3, -4, -5, -6, -127, -128, -129,
            -(1L shl 31), -(1L shl 32), -(1L shl 32) - 1, -(1L shl 40), Long.MIN_VALUE,
        )
        for (value in values) {
            val out = java.io.ByteArrayOutputStream()
            dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(value, out)
            val bytes = out.toByteArray()
            val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(bytes, 0, bytes.size)
            assertNotNull(parsed)
            assertEquals(value, parsed!!.first)
            assertEquals(bytes.size, parsed.second)
        }
    }

    @Test
    fun udpVarintSignedEncodingMatchesOfficial() {
        fun bytes(value: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(value, out)
            return out.toByteArray()
        }
        fun b(vararg v: Int) = v.map { it.toByte() }.toByteArray()

        // ~i then compact / 0xFC, not absolute value (so -5 is F8 04, not F8 05).
        assertArrayEquals(b(0xFC), bytes(-1))
        assertArrayEquals(b(0xFD), bytes(-2))
        assertArrayEquals(b(0xFE), bytes(-3))
        assertArrayEquals(b(0xFF), bytes(-4))
        assertArrayEquals(b(0xF8, 0x04), bytes(-5))
        assertArrayEquals(b(0xF8, 0x05), bytes(-6))
        assertArrayEquals(b(0xF8, 0x7E), bytes(-127))
        assertArrayEquals(b(0xF8, 0x80, 0x80), bytes(-129))
        // Upper end of the official signed window: -2^32 still uses 0xF8.
        assertArrayEquals(
            b(0xF8, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
            bytes(-(1L shl 32)),
        )
        // Below -2^32 the official encoder skips 0xF8 and writes 0xF4.
        assertArrayEquals(
            b(0xF4, 0xFF, 0xFF, 0xFF, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF),
            bytes(-(1L shl 32) - 1),
        )
        assertArrayEquals(
            b(0xF4, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(Long.MIN_VALUE),
        )
    }

    @Test
    fun udpVarintRejectsDeepF8Nesting() {
        fun nestedF8(count: Int): ByteArray = ByteArray(count + 1) { i ->
            if (i < count) 0xF8.toByte() else 0x02
        }
        // Official decode_next_int allows 8 nested 0xF8 prefixes (levels 0..7).
        val ok = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(nestedF8(8), 0, 9)
        assertNotNull(ok)
        assertEquals(2L, ok!!.first)
        assertEquals(9, ok.second)
        // A 9th 0xF8 hits recursionLevel >= 8 and is rejected.
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(nestedF8(9), 0, 10))
    }

    @Test
    fun udpLegacyOpusFrameParsing() {
        // Build a server->client legacy Opus packet:
        // [header (type=4<<5 | context 0)][senderSession varint][frameNumber varint][size varint][opus]
        val session = 42
        val frameNumber = 7L
        val opusPayload = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val out = java.io.ByteArrayOutputStream()
        out.write((4 shl 5) and 0xff)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(session.toLong(), out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(frameNumber, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(opusPayload.size.toLong(), out)
        out.write(opusPayload)
        val packet = out.toByteArray()

        val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(packet)
        assertNotNull(parsed)
        assertEquals(session, parsed!!.first)
        assertArrayEquals(opusPayload, parsed.second)
    }

    @Test
    fun udpLegacyOpusRejectsWrongTypeAndTruncated() {
        // Wrong type (CELT_alpha = 0) should be rejected.
        val bad = byteArrayOf(0, 0, 1)
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(bad))
        // Truncated (size claims more data than present) should be rejected.
        val truncated = byteArrayOf((4 shl 5).toByte(), 5, 1, 0x50)
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.parseLegacyOpus(truncated))
    }

    @Test
    fun udpTalkHeaderByte_keepsTheWholeFiveBitRange() {
        // 31 is the highest representable id (server loopback), not 30.
        val header = dev.woms.mumdroid.core.net.UdpPacketCodec.talkHeaderByte(31)
        assertEquals(31, header.toInt() and 0x1f)
        assertEquals(
            dev.woms.mumdroid.core.net.UdpType.VOICE_OPUS,
            (header.toInt() ushr 5) and 0x07,
        )
    }

    @Test
    fun udpTalkHeaderByte_refusesOutOfRangeTargetsInsteadOfFoldingThem() {
        // 32 & 0x1f would silently become 0, i.e. a channel-wide broadcast.
        assertThrows(IllegalArgumentException::class.java) {
            dev.woms.mumdroid.core.net.UdpPacketCodec.talkHeaderByte(32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dev.woms.mumdroid.core.net.UdpPacketCodec.encodeLegacyOpus(
                byteArrayOf(1, 2, 3),
                isLastFrame = false,
                frameNumber = 0L,
                target = 32,
            )
        }
    }

    @Test
    fun udpLegacyOpusEncode_stampsFrameAndLastFlag() {
        val payload = byteArrayOf(1, 2, 3)
        val packet = dev.woms.mumdroid.core.net.UdpPacketCodec.encodeLegacyOpus(
            payload,
            isLastFrame = true,
            frameNumber = 6L,
        )
        assertEquals(
            dev.woms.mumdroid.core.net.UdpPacketCodec.talkHeaderByte(),
            packet[0],
        )
        val frame = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, 1, packet.size)
        assertNotNull(frame)
        assertEquals(6L, frame!!.first)
        val size = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, frame.second, packet.size)
        assertNotNull(size)
        assertEquals(0x2000L or 3L, size!!.first)
        assertArrayEquals(payload, packet.copyOfRange(size.second, packet.size))
    }

    @Test
    fun udpLegacyPingEncodeRoundTrip() {
        val ts = 0x1122334455667788L
        val packet = dev.woms.mumdroid.core.net.UdpPacketCodec.encodePing(ts)
        assertEquals(
            dev.woms.mumdroid.core.net.UdpType.PING,
            (packet[0].toInt() ushr 5) and 0x07,
        )
        assertEquals(ts, dev.woms.mumdroid.core.net.UdpPacketCodec.readPingTimestamp(packet))
        assertNull(dev.woms.mumdroid.core.net.UdpPacketCodec.readPingTimestamp(byteArrayOf(0x20)))
    }

    // ---- ProtoUdpCodec (new Mumble >= 1.5.0 protobuf UDP framing) ----

    @Test
    fun protoUdpAudioRoundTrip() {
        val opusPayload = byteArrayOf(0x0a, 0x0b, 0x0c)
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodeAudio(
            frameNumber = 1234L,
            opusData = opusPayload,
            target = 0,
        )
        assertEquals(0, packet[0].toInt()) // Audio header byte
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet.copyOfRange(1, packet.size))
        assertNotNull(decoded)
    }

    @Test
    fun protoUdpAudioDecodeFields() {
        val opusPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodeAudio(77L, opusPayload, target = 0)
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet.copyOfRange(1, packet.size))
        assertNotNull(decoded)
        assertArrayEquals(opusPayload, decoded!!.payload)
        org.junit.Assert.assertFalse(decoded.isLastFrame)
        val viaOffset = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(packet, 1, packet.size - 1)
        assertNotNull(viaOffset)
        assertArrayEquals(opusPayload, viaOffset!!.payload)
    }

    @Test
    fun protoUdpServerAudioDecode() {
        val audio = dev.woms.mumdroid.core.udpproto.Audio.newBuilder()
            .setContext(2)
            .setSenderSession(42)
            .setFrameNumber(9L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(byteArrayOf(9, 8, 7)))
            .setIsTerminator(true)
            .build()
        val body = audio.toByteArray()
        val decoded = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(body)
        assertNotNull(decoded)
        assertEquals(42, decoded!!.session)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.payload)
        assertTrue(decoded.isLastFrame)
    }

    @Test
    fun protoUdpPingRoundTrip() {
        val packet = dev.woms.mumdroid.core.net.ProtoUdpCodec.encodePing(987654321L)
        assertEquals(1, packet[0].toInt()) // Ping header byte
        val ts = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodePing(packet.copyOfRange(1, packet.size))
        assertNotNull(ts)
        assertEquals(987654321L, ts!!.toLong())
    }

    @Test
    fun protoUdpDecodeRejectsInvalid() {
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(ByteArray(0)))
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodePing(ByteArray(0)))
        // Audio message without opus data is invalid per the official decoder.
        val empty = dev.woms.mumdroid.core.udpproto.Audio.newBuilder().setTarget(0).build().toByteArray()
        assertNull(dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(empty))
    }

    @Test
    fun outgoingVoice_stampsTenMsFrameNumbers() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.framesPerPacket = 2
        val p0 = requireNotNull(udp.buildTunnelPacket(byteArrayOf(1, 2, 3), false, 2))
        val p1 = requireNotNull(udp.buildTunnelPacket(byteArrayOf(4, 5, 6), false, 2))
        val p2 = requireNotNull(udp.buildTunnelPacket(byteArrayOf(7, 8, 9), false, 2))
        fun frameNumber(packet: ByteArray): Long {
            val parsed = dev.woms.mumdroid.core.net.UdpPacketCodec.readVarInt(packet, 1, packet.size)
            return parsed!!.first
        }
        assertEquals(0L, frameNumber(p0))
        assertEquals(2L, frameNumber(p1))
        assertEquals(4L, frameNumber(p2))
        udp.close()
    }

    @Test
    fun protoOutgoingVoice_stampsTenMsFrameNumbers() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.protobufMode = true
        udp.framesPerPacket = 2
        val p0 = requireNotNull(udp.buildTunnelPacket(byteArrayOf(1, 2, 3), false, 2))
        val p1 = requireNotNull(udp.buildTunnelPacket(byteArrayOf(4, 5, 6), false, 2))
        assertEquals(0, p0[0].toInt())
        val d0 = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(p0.copyOfRange(1, p0.size))
        val d1 = dev.woms.mumdroid.core.net.ProtoUdpCodec.decodeAudio(p1.copyOfRange(1, p1.size))
        assertEquals(0L, d0!!.frameNumber)
        assertEquals(2L, d1!!.frameNumber)
        udp.close()
    }

    @Test
    fun playTunneled_protobufDispatchesAudio() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        udp.protobufMode = true
        var capturedSession = -1
        var frame = -1L
        var capturedPayload: ByteArray? = null
        udp.setListener(object : dev.woms.mumdroid.core.net.UdpVoiceManager.Listener {
            override fun onAudioPacket(
                session: Int,
                frameNumber: Long,
                payload: ByteArray,
                isLastFrame: Boolean,
                context: dev.woms.mumdroid.core.model.AudioContext,
            ) {
                capturedSession = session
                frame = frameNumber
                capturedPayload = payload
            }
            override fun onUdpPing(rttMillis: Long) {}
            override fun onUdpConnected() {}
            override fun onUdpError(message: String) {}
        })
        val opusPayload = byteArrayOf(9, 8, 7, 6)
        val audio = dev.woms.mumdroid.core.udpproto.Audio.newBuilder()
            .setContext(0)
            .setSenderSession(42)
            .setFrameNumber(10L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(opusPayload))
            .build()
        val proto = audio.toByteArray()
        val body = ByteArray(1 + proto.size)
        body[0] = 0
        System.arraycopy(proto, 0, body, 1, proto.size)
        udp.playTunneled(body)
        assertEquals(42, capturedSession)
        assertEquals(10L, frame)
        assertArrayEquals(opusPayload, capturedPayload)
        udp.close()
    }

    @Test
    fun playTunneled_legacyDispatchesAudio() {
        val udp = dev.woms.mumdroid.core.net.UdpVoiceManager("127.0.0.1", 64738)
        var capturedSession = -1
        var capturedPayload: ByteArray? = null
        udp.setListener(object : dev.woms.mumdroid.core.net.UdpVoiceManager.Listener {
            override fun onAudioPacket(
                session: Int,
                frameNumber: Long,
                payload: ByteArray,
                isLastFrame: Boolean,
                context: dev.woms.mumdroid.core.model.AudioContext,
            ) {
                capturedSession = session
                capturedPayload = payload
            }
            override fun onUdpPing(rttMillis: Long) {}
            override fun onUdpConnected() {}
            override fun onUdpError(message: String) {}
        })
        val opusPayload = byteArrayOf(1, 2, 3, 4, 5)
        val out = java.io.ByteArrayOutputStream()
        out.write((dev.woms.mumdroid.core.net.UdpType.VOICE_OPUS shl 5) or 0)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(7, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(4, out)
        dev.woms.mumdroid.core.net.UdpPacketCodec.writeVarInt(opusPayload.size.toLong(), out)
        out.write(opusPayload)
        udp.playTunneled(out.toByteArray())
        assertEquals(7, capturedSession)
        assertArrayEquals(opusPayload, capturedPayload)
        udp.close()
    }
}
