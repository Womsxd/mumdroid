package dev.woms.mumdroid.core.net

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import dev.woms.mumdroid.core.udpproto.Audio
import dev.woms.mumdroid.core.udpproto.Ping

/**
 * Encoder/decoder for the **new** (Mumble >= 1.5.0) UDP voice framing.
 *
 * Wire layout: `[1 header byte][serialized protobuf message]` where the
 * header byte selects the message type (mirrors `UDPMessageType` in
 * Mumble::Protocol):
 *  - 0 = Audio
 *  - 1 = Ping
 *
 * The same framing is used for OCB2-encrypted datagrams and for plaintext
 * bodies inside TCP UDPTunnel messages, mirroring the official client's
 * `UDPAudioEncoder` / `UDPPingEncoder` / `UDPDecoder` in protobuf mode.
 */
object ProtoUdpCodec {

    const val HEADER_AUDIO = 0
    const val HEADER_PING = 1

    /** A decoded server->client audio packet. */
    class DecodedAudio(
        val session: Int,
        val payload: ByteArray,
        val isLastFrame: Boolean,
        val frameNumber: Long = 0L,
    )

    /**
     * Encodes a client->server audio packet (`[0x00][Audio protobuf]`),
     * mirroring `UDPAudioEncoder::prepareAudioPacket_protobuf` +
     * `updateAudioPacket_protobuf` for the client role.
     */
    fun encodeAudio(
        frameNumber: Long,
        opusData: ByteArray,
        target: Int = 0,
        isLastFrame: Boolean = false,
    ): ByteArray {
        val message = Audio.newBuilder()
            .setTarget(target)
            .setFrameNumber(frameNumber)
            .setOpusData(ByteString.copyFrom(opusData))
            .setIsTerminator(isLastFrame)
            .build()
        return prependHeader(HEADER_AUDIO, message.toByteArray())
    }

    /** Decodes a server->client audio packet body. Returns null when invalid. */
    fun decodeAudio(body: ByteArray): DecodedAudio? =
        decodeAudio(body, 0, body.size)

    /**
     * Decodes `body[offset, offset+length)` as an Audio protobuf. Used so the
     * UDP header byte can stay in the decrypted buffer instead of slicing it.
     */
    fun decodeAudio(body: ByteArray, offset: Int, length: Int): DecodedAudio? {
        if (!validSlice(body, offset, length) || length == 0) return null
        val audio = try {
            Audio.parseFrom(CodedInputStream.newInstance(body, offset, length))
        } catch (_: Exception) {
            return null
        }
        // Audio packets without audio data are invalid (mirrors the official decoder).
        if (audio.opusData.isEmpty) return null
        return DecodedAudio(
            session = audio.senderSession,
            payload = audio.opusData.toByteArray(),
            isLastFrame = audio.isTerminator,
            frameNumber = audio.frameNumber,
        )
    }

    /**
     * Encodes a connectivity ping packet (`[0x01][Ping protobuf]`).
     *
     * [requestExtended] maps to `request_extended_information` and is used by
     * the unconnected server-list pinger (official ConnectDialog) so the
     * reply includes user counts and the server version.
     */
    fun encodePing(timestamp: Long, requestExtended: Boolean = false): ByteArray {
        val builder = Ping.newBuilder().setTimestamp(timestamp)
        if (requestExtended) {
            builder.setRequestExtendedInformation(true)
        }
        return prependHeader(HEADER_PING, builder.build().toByteArray())
    }

    /** Decodes a ping packet body. @return the echoed timestamp or null when invalid. */
    fun decodePing(body: ByteArray): Long? = decodePing(body, 0, body.size)

    /** Decodes `body[offset, offset+length)` as a Ping protobuf. */
    fun decodePing(body: ByteArray, offset: Int, length: Int): Long? {
        if (!validSlice(body, offset, length) || length == 0) return null
        val ping = try {
            Ping.parseFrom(CodedInputStream.newInstance(body, offset, length))
        } catch (_: Exception) {
            return null
        }
        return ping.timestamp
    }

    private fun prependHeader(header: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(1 + payload.size)
        out[0] = header.toByte()
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    private fun validSlice(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 0 || offset > buf.size) return false
        return length <= buf.size - offset
    }
}
