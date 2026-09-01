package dev.woms.mumdroid.core.net

import java.io.ByteArrayOutputStream

/**
 * Helpers to encode / decode the **legacy** Mumble UDP packet framing used by
 * the voice channel (mirrors the official `Mumble::Protocol::PacketDataStream`
 * and `UDPDecoder`/`UDPAudioEncoder`).
 *
 * Legacy UDP layout (after OCB2 decryption, or as the plaintext body of a
 * force-TCP UDP_TUNNEL message):
 * ```
 * byte  header        = (type << 5) | target/context   // type: 0=CELT_alpha 1=Ping 2=Speex 3=CELT_beta 4=Opus
 * varint senderSession                                // server -> client only
 * varint frameNumber
 * [Opus] varint size   (bit 0x2000 = isLastFrame, low 13 bits = payload size)
 *        bytes  opus payload
 * [CELT/Speex] TOC-chained frames
 * [optional] 3 floats positional data
 * ```
 *
 * The integer fields are encoded with the **Mumble varint** scheme used by
 * `PacketDataStream` (not plain LEB128): short positive values are packed into
 * 1-4 bytes using a 2-4 bit length prefix in the first byte, while larger
 * values use the 0xF0 (32-bit) / 0xF4 (64-bit) escape markers. Decoding is the
 * inverse of `PacketDataStream::decode_next_int`.
 */
object UdpPacketCodec {

    /**
     * Official `PacketDataStream::decode_next_int` refuses further `0xF8`
     * nesting once this depth is reached (malicious all-`0xF8` packets).
     */
    private const val MAX_VARINT_RECURSION = 8

    /**
     * Encodes [value] with the Mumble `PacketDataStream` varint scheme.
     *
     * Positive values use the compact prefix encoding. Values in
     * `[-2^32, -1]` use the official signed markers: `0xFC` (2-bit `~i` for
     * -1..-4) or `0xF8` followed by the varint of `~i` (bitwise NOT, not
     * absolute value). More negative values skip that branch and are written
     * as a 64-bit `0xF4` quantity, matching `operator<<(quint64)`.
     *
     * Only non-negative values are produced by the voice pipeline; the signed
     * path exists so accidental negatives stay wire-compatible.
     */
    fun writeVarInt(value: Long, out: ByteArrayOutputStream) {
        var i = value

        // Official: (i & 0x8000…0) && (~i < 0x100000000) — i.e. [-2^32, -1].
        if (i < 0 && i.inv() < 0x100000000L) {
            i = i.inv()
            if (i <= 0x3L) {
                // Special case for -1..-4: high 6 bits 111111 + 2 bits of ~i.
                out.write(0xFC or i.toInt())
                return
            }
            // Marker 0xF8: the following varint is ~value encoded as unsigned.
            out.write(0xF8)
        }

        // Remaining encoding treats [i] as unsigned quint64. Compact / 32-bit
        // arms require a non-negative value; anything still negative (outside
        // [-2^32, -1]) takes the 64-bit 0xF4 path, as in the official else.
        when {
            i >= 0L && i < 0x80L -> {
                // 7-bit positive -> single byte, high bit clear.
                out.write(i.toInt())
            }
            i >= 0L && i < 0x4000L -> {
                // 14-bit positive -> first byte high bits 10.
                out.write(((i shr 8).toInt() or 0x80) and 0xff)
                out.write((i and 0xff).toInt())
            }
            i >= 0L && i < 0x200000L -> {
                // 21-bit positive -> first byte high bits 110.
                out.write(((i shr 16).toInt() or 0xC0) and 0xff)
                out.write(((i shr 8) and 0xff).toInt())
                out.write((i and 0xff).toInt())
            }
            i >= 0L && i < 0x10000000L -> {
                // 28-bit positive -> first byte high bits 1110.
                out.write(((i shr 24).toInt() or 0xE0) and 0xff)
                out.write(((i shr 16) and 0xff).toInt())
                out.write(((i shr 8) and 0xff).toInt())
                out.write((i and 0xff).toInt())
            }
            i >= 0L && i < 0x100000000L -> {
                // 32-bit positive -> marker 0xF0 + 4 big-endian bytes.
                out.write(0xF0)
                out.write(((i shr 24) and 0xff).toInt())
                out.write(((i shr 16) and 0xff).toInt())
                out.write(((i shr 8) and 0xff).toInt())
                out.write((i and 0xff).toInt())
            }
            else -> {
                // 64-bit unsigned -> marker 0xF4 + 8 big-endian bytes.
                out.write(0xF4)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((i ushr shift) and 0xffL).toInt())
                }
            }
        }
    }

    /**
     * Reads a Mumble `PacketDataStream` varint from [data] starting at [offset].
     *
     * @return a pair of the decoded value and the index one past the last byte
     *         consumed, or null when the input is invalid / truncated.
     */
    fun readVarInt(data: ByteArray, offset: Int, end: Int): Pair<Long, Int>? =
        readVarInt(data, offset, end, recursionLevel = 0)

    private fun readVarInt(
        data: ByteArray,
        offset: Int,
        end: Int,
        recursionLevel: Int,
    ): Pair<Long, Int>? {
        if (offset >= end) return null
        var pos = offset
        val first = data[pos].toInt() and 0xff
        pos++

        fun next(): Int {
            if (pos >= end) throw PacketFormatException("varint truncated")
            return data[pos++].toInt() and 0xff
        }

        try {
            return when {
                (first and 0x80) == 0x00 -> {
                    // 7-bit positive number.
                    (first and 0x7f).toLong() to pos
                }
                (first and 0xC0) == 0x80 -> {
                    // 14-bit positive number: 6 bits + 1 following byte.
                    (((first and 0x3f).toLong() shl 8) or next().toLong()) to pos
                }
                (first and 0xF0) == 0xF0 -> {
                    // Special markers: 0xF0 (32-bit), 0xF4 (64-bit),
                    // 0xF8 (negative varint), 0xFC (-1..-4).
                    when (first and 0xFC) {
                        0xF0 -> {
                            // 32-bit positive integer in 4 big-endian bytes.
                            var r = 0L
                            repeat(4) { r = (r shl 8) or next().toLong() }
                            r to pos
                        }
                        0xF4 -> {
                            // 64-bit positive integer in 8 big-endian bytes.
                            var r = 0L
                            repeat(8) { r = (r shl 8) or next().toLong() }
                            r to pos
                        }
                        0xF8 -> {
                            // Negative number: follow with another varint of ~value.
                            // Official decode_next_int caps nesting at 8.
                            if (recursionLevel >= MAX_VARINT_RECURSION) return null
                            val inner = readVarInt(data, pos, end, recursionLevel + 1)
                                ?: return null
                            inner.first.inv() to inner.second
                        }
                        0xFC -> {
                            // -1..-4 encoded directly in the low 2 bits.
                            (first and 0x03).toLong().inv() to pos
                        }
                        else -> null
                    }
                }
                (first and 0xF0) == 0xE0 -> {
                    // 28-bit positive number: 4 bits + 3 following bytes.
                    var r = (first and 0x0f).toLong() shl 24
                    r = r or (next().toLong() shl 16)
                    r = r or (next().toLong() shl 8)
                    r = r or next().toLong()
                    r to pos
                }
                (first and 0xE0) == 0xC0 -> {
                    // 21-bit positive number: 5 bits + 2 following bytes.
                    var r = (first and 0x1f).toLong() shl 16
                    r = r or (next().toLong() shl 8)
                    r = r or next().toLong()
                    r to pos
                }
                else -> null
            }
        } catch (e: PacketFormatException) {
            return null
        }
    }

    /** Legacy header byte for a normal Opus talk packet (`type << 5`). */
    fun talkHeaderByte(): Byte = ((UdpType.VOICE_OPUS shl 5) or 0).toByte()

    /**
     * Client→server legacy Opus body: `[header][frameNumber][size][opus]`.
     * The server-bound packet has no sender-session varint.
     */
    fun encodeLegacyOpus(
        payload: ByteArray,
        isLastFrame: Boolean,
        frameNumber: Long,
        header: Byte = talkHeaderByte(),
    ): ByteArray {
        val out = ByteArrayOutputStream(1 + 6 + 2 + payload.size)
        out.write(header.toInt() and 0xff)
        writeVarInt(frameNumber, out)
        val sizeField = payload.size or if (isLastFrame) 0x2000 else 0
        writeVarInt(sizeField.toLong(), out)
        out.write(payload)
        return out.toByteArray()
    }

    /** Client→server legacy ping: `[header][varint timestamp]`. */
    fun encodePing(timestamp: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        out.write((UdpType.PING shl 5) and 0xff)
        writeVarInt(timestamp, out)
        return out.toByteArray()
    }

    /** Echoed timestamp from a legacy ping reply, or null when truncated. */
    fun readPingTimestamp(plain: ByteArray): Long? {
        if (plain.size <= 1) return null
        return readVarInt(plain, 1, plain.size)?.first
    }

    /** Full legacy Opus packet with frame metadata. */
    data class LegacyOpusPacket(
        val session: Int,
        val frameNumber: Long,
        val payload: ByteArray,
        val isLastFrame: Boolean,
    )

    /**
     * Parses a legacy server->client Opus audio packet:
     * `[header][senderSession varint][frameNumber varint][size varint][opus data]`.
     * Returns the sender session and the raw Opus payload, or null when the
     * packet is not a valid Opus frame.
     */
    fun parseLegacyOpus(body: ByteArray): Pair<Int, ByteArray>? {
        val p = parseLegacyOpusFull(body) ?: return null
        return p.session to p.payload
    }

    /** Full parse including frameNumber / isLastFrame (needed for PLC & reset). */
    fun parseLegacyOpusFull(body: ByteArray): LegacyOpusPacket? {
        if (body.size < 3) return null
        val header = body[0].toInt() and 0xff
        val type = (header ushr 5) and 0x07
        if (type != UdpType.VOICE_OPUS) return null
        val session = readVarInt(body, 1, body.size) ?: return null
        var pos = session.second
        val frame = readVarInt(body, pos, body.size) ?: return null
        pos = frame.second
        // Size varint (low 13 bits = size, bit 0x2000 = last frame).
        val sizeField = readVarInt(body, pos, body.size) ?: return null
        pos = sizeField.second
        val payloadSize = (sizeField.first and 0x1fff).toInt()
        val isLast = (sizeField.first and 0x2000L) != 0L
        if (payloadSize <= 0 || pos + payloadSize > body.size) return null
        return LegacyOpusPacket(
            session = session.first.toInt(),
            frameNumber = frame.first,
            payload = body.copyOfRange(pos, pos + payloadSize),
            isLastFrame = isLast,
        )
    }

    private class PacketFormatException(message: String) : Exception(message)
}
