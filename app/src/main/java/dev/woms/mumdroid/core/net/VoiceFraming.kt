package dev.woms.mumdroid.core.net

import android.os.SystemClock
import dev.woms.mumdroid.core.audio.VoiceFrameCounter

/**
 * Wire-format layer for the voice channel, mirroring the official
 * `UDPDecoder` / `UDPAudioEncoder` split: it builds outgoing voice/ping
 * bodies and decodes incoming plaintext bodies in both protocol
 * generations.
 *
 * All inputs and outputs are **plaintext** `[header|payload]` bodies —
 * OCB2 encryption/decryption of the surrounding datagram happens in
 * [UdpVoiceCrypto]; this class never sees ciphertext.
 *
 * ## Framing
 *  - **Legacy** (`protobufMode == false`, servers < 1.5.0):
 *    `(type << 5) | target/context` where the type selects the message kind:
 *    0 = CELT Alpha, 1 = Ping, 2 = Speex, 3 = CELT Beta, 4 = Opus.
 *  - **Protobuf** (`protobufMode == true`, servers >= 1.5.0):
 *    the header byte directly selects the message type
 *    ([ProtoUdpCodec.HEADER_AUDIO] = Audio, [ProtoUdpCodec.HEADER_PING] = Ping)
 *    followed by a serialized MumbleUDP protobuf message.
 */
class VoiceFraming(
    /** Monotonic ms source for ping timestamps (official `QElapsedTimer`). */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /**
     * The UDP framing negotiated with the server: `true` for the protobuf
     * framing of Mumble >= 1.5.0 servers, `false` for the legacy framing.
     * Determined from the server's reported protocol version.
     */
    @Volatile
    var protobufMode: Boolean = false

    /**
     * Outgoing `frameNumber` in 10 ms units (official `iFrameCounter`).
     * A 20 ms packet is stamped N then advances to N+2.
     */
    private val frameCounter = VoiceFrameCounter(clock = clock)

    /** Result of decoding a plaintext body. */
    sealed interface Decoded {
        /** Encoded Opus from a remote user. */
        data class Audio(
            val session: Int,
            val frameNumber: Long,
            val payload: ByteArray,
            val isLastFrame: Boolean,
            /**
             * Server→client context of this audio (0 normal / 1 shout /
             * 2 whisper / 3 listener), mapped to
             * [dev.woms.mumdroid.core.model.AudioContext].
             */
            val context: dev.woms.mumdroid.core.model.AudioContext =
                dev.woms.mumdroid.core.model.AudioContext.NORMAL,
        ) : Decoded

        /** A ping reply carrying the echoed timestamp. */
        data class Ping(val timestamp: Long) : Decoded

        /** Nothing this client consumes (unknown header, obsolete codec, malformed). */
        data object Unknown : Decoded
    }

    /** Resets the outgoing frame-number counter (new voice session). */
    fun reset() {
        frameCounter.reset()
    }

    /**
     * Plaintext connectivity-ping body (protobuf or legacy). Used encrypted
     * on UDP and as a UDPTunnel payload so murmur sets `aiUdpFlag = 0`.
     */
    fun pingBody(): ByteArray {
        val timestamp = clock()
        return if (protobufMode) {
            ProtoUdpCodec.encodePing(timestamp)
        } else {
            UdpPacketCodec.encodePing(timestamp)
        }
    }

    /**
     * Builds the full plaintext voice packet body (header + payload in the
     * negotiated framing) used both as the OCB2-encrypted UDP datagram and as
     * the plaintext body of a force-TCP UDPTunnel message.
     *
     * [target] is the voice-target id: 0 for regular speech, 1..30 for a
     * registered shout/whisper target. Official `UDPAudioEncoder` refuses
     * anything that does not fit the five legacy header bits, and the desktop
     * client never encodes audio at all while its target is unresolvable; this
     * returns null for the same inputs so a broken whisper cannot silently
     * degrade into a channel-wide broadcast. The frame number is only consumed
     * when the packet is actually built.
     *
     * @return the packet body, or null when [target] is out of range.
     */
    fun buildVoiceBody(
        payload: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int,
        target: Int = dev.woms.mumdroid.core.model.VoiceTargetId.REGULAR_SPEECH,
    ): ByteArray? {
        if (target !in 0..dev.woms.mumdroid.core.model.VoiceTargetId.SERVER_LOOPBACK) return null
        val frameNumber = frameCounter.allocate(frameCount)
        return if (protobufMode) {
            ProtoUdpCodec.encodeAudio(frameNumber, payload, target = target, isLastFrame = isLastFrame)
        } else {
            UdpPacketCodec.encodeLegacyOpus(payload, isLastFrame, frameNumber, target = target)
        }
    }

    /**
     * Decodes a decrypted datagram body (`[header|payload]`), routing strictly
     * by the negotiated [protobufMode] (official `UDPDecoder::decode`,
     * client role). See [decodeLegacy] for the legacy-mode dispatch order.
     */
    fun decodeDatagram(plain: ByteArray): Decoded {
        if (plain.isEmpty()) return Decoded.Unknown
        return if (protobufMode) {
            decodeProtobuf(plain)
        } else {
            decodeLegacy(plain)
        }
    }

    private fun decodeProtobuf(plain: ByteArray): Decoded =
        when (plain[0].toInt() and 0xff) {
            ProtoUdpCodec.HEADER_PING -> {
                val ts = ProtoUdpCodec.decodePing(plain, 1, plain.size - 1)
                if (ts != null && ts > 0) Decoded.Ping(ts) else Decoded.Unknown
            }
            ProtoUdpCodec.HEADER_AUDIO -> {
                val audio = ProtoUdpCodec.decodeAudio(plain, 1, plain.size - 1)
                    ?: return Decoded.Unknown
                Decoded.Audio(
                    audio.session,
                    audio.frameNumber,
                    audio.payload,
                    audio.isLastFrame,
                    dev.woms.mumdroid.core.model.AudioContext.fromWire(audio.context),
                )
            }
            else -> Decoded.Unknown
        }

    private fun decodeLegacy(plain: ByteArray): Decoded {
        // Extended legacy ping replies from the server are header-less 24-byte
        // blocks: [4B server version (BE)][8B echoed timestamp][4B users]
        // [4B max users][4B max bandwidth]. This check MUST come before the
        // protobuf-ping-header check below: the first byte is the most
        // significant byte of the BE server version, and every major=1
        // server (i.e. virtually all of them) carries 0x01 there — treating
        // the header check as first-class would swallow the extended ping as
        // a bogus protobuf ping (protobuf parsing is lenient, so it can
        // "succeed" with a garbage timestamp). The official UDPDecoder
        // (MumbleProtocol.cpp) checks the header first and returns the
        // protobuf result unconditionally, so it drops these extended pings
        // whenever the 23-byte tail happens to parse; routing by length here
        // is strictly more robust, and real protobuf pings never reach 24
        // bytes (a serialised Ping body tops out around 14 bytes), so the
        // size check is unambiguous. An undecodable 24-byte block is dropped.
        if (plain.size == 24) {
            val ts = ServerPingCodec.decode(plain)?.timestamp ?: return Decoded.Unknown
            return Decoded.Ping(ts)
        }

        // Mirrors the official UDPDecoder::decode (client role): in legacy mode
        // a packet whose first byte equals the *protobuf* ping header byte
        // (0x01) is treated as a protobuf-framed ping, since peers whose version
        // we have not negotiated may already speak the new framing.
        if (plain[0].toInt() == ProtoUdpCodec.HEADER_PING) {
            val ts = ProtoUdpCodec.decodePing(plain, 1, plain.size - 1)
            if (ts != null && ts > 0) {
                return Decoded.Ping(ts)
            }
        }

        val header = plain[0].toInt() and 0xff
        val type = (header ushr 5) and 0x07
        return when (type) {
            UdpType.PING -> {
                val ts = UdpPacketCodec.readPingTimestamp(plain)
                if (ts != null) Decoded.Ping(ts) else Decoded.Unknown
            }
            UdpType.VOICE_OPUS -> {
                val p = UdpPacketCodec.parseLegacyOpusFull(plain) ?: return Decoded.Unknown
                Decoded.Audio(
                    p.session,
                    p.frameNumber,
                    p.payload,
                    p.isLastFrame,
                    dev.woms.mumdroid.core.model.AudioContext.fromWire(p.context),
                )
            }
            UdpType.VOICE_CELT_ALPHA, UdpType.VOICE_CELT_BETA, UdpType.VOICE_SPEEX ->
                // Obsolete codecs are no longer supported by the official client.
                Decoded.Unknown
            else -> Decoded.Unknown
        }
    }

    /**
     * Decodes a plaintext UDPTunnel body. Tries protobuf then legacy so a
     * fallback session still plays when the negotiated framing disagrees
     * with the tunneled header — deliberately independent of [protobufMode]
     * (force-TCP only ever used the strict audio branch).
     */
    fun decodeTunneled(body: ByteArray): Decoded.Audio? {
        if (body.isEmpty()) return null
        val header = body[0].toInt() and 0xff
        if (header == ProtoUdpCodec.HEADER_AUDIO) {
            ProtoUdpCodec.decodeAudio(body, 1, body.size - 1)?.let {
                return Decoded.Audio(
                    it.session,
                    it.frameNumber,
                    it.payload,
                    it.isLastFrame,
                    dev.woms.mumdroid.core.model.AudioContext.fromWire(it.context),
                )
            }
        }
        val legacy = UdpPacketCodec.parseLegacyOpusFull(body) ?: return null
        return Decoded.Audio(
            legacy.session,
            legacy.frameNumber,
            legacy.payload,
            legacy.isLastFrame,
            dev.woms.mumdroid.core.model.AudioContext.fromWire(legacy.context),
        )
    }
}
