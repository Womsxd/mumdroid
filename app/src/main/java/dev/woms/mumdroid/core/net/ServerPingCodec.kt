package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.udpproto.Ping
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encode / decode plaintext UDP pings used to probe a server **before** a
 * TCP login, matching official `UDPPingEncoder` / `UDPDecoder` in
 * ConnectDialog.
 *
 * Two request formats are sent (version unknown):
 *  - Legacy 12-byte extended ping: `[4B zero][8B timestamp LE]`
 *  - Protobuf ping (`[0x01][MumbleUDP.Ping]`) with
 *    `request_extended_information = true`
 *
 * Replies:
 *  - Legacy 24 bytes:
 *    `[4B version BE][8B echoed timestamp][4B users BE][4B maxUsers BE][4B bandwidth BE]`
 *  - Protobuf: header `0x01` plus a `Ping` carrying version v2 and counts
 */
enum class ServerPingPacket {
    /** Legacy 12-byte extended ping (`UDPPingEncoder` when version < 1.5.0). */
    LEGACY,
    /** Protobuf ping (`UDPPingEncoder` when version >= 1.5.0). */
    PROTOBUF,
}

object ServerPingCodec {

    const val LEGACY_REQUEST_SIZE = 12
    const val LEGACY_REPLY_SIZE = 24

    /**
     * Which ping datagrams to send, matching official
     * `ConnectDialog::sendPing`:
     *  - version unknown → legacy **and** protobuf
     *  - known version < 1.5.0 → legacy only
     *  - known version ≥ 1.5.0 → protobuf only
     */
    fun packetsToSend(version: String?, versionPrecise: Boolean = false): List<ServerPingPacket> {
        if (version.isNullOrEmpty()) {
            return listOf(ServerPingPacket.LEGACY, ServerPingPacket.PROTOBUF)
        }
        return if (isProtobufEra(version, versionPrecise)) {
            listOf(ServerPingPacket.PROTOBUF)
        } else {
            listOf(ServerPingPacket.LEGACY)
        }
    }

    fun packetsToSend(info: ServerPingInfo?): List<ServerPingPacket> {
        if (info == null || !info.reachable) {
            return packetsToSend(version = null)
        }
        return packetsToSend(info.version, info.versionPrecise)
    }

    /** Official `PROTOBUF_INTRODUCTION_VERSION` is 1.5.0. */
    fun isProtobufEra(version: String, versionPrecise: Boolean): Boolean {
        if (versionPrecise) return true
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > 1 || (major == 1 && minor >= 5)
    }

    data class Decoded(
        val timestamp: Long,
        val users: Int? = null,
        val maxUsers: Int? = null,
        val version: String? = null,
        val bandwidth: Int? = null,
        /**
         * True when [version] comes from a protobuf ping reply carrying the
         * exact v2 version. Legacy 24-byte replies carry the 16.8.8 version,
         * which the server saturates (e.g. 1.5.857 -> 1.5.255), so those must
         * never overwrite an exact v2 value (mirrors official behavior where
         * protobuf replies win for servers >= 1.5.0).
         */
        val versionIsV2: Boolean = false,
    )

    /** Client → server legacy extended-information request (12 bytes). */
    fun encodeLegacy(timestamp: Long): ByteArray {
        val buf = ByteBuffer.allocate(LEGACY_REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)
        buf.putLong(timestamp)
        return buf.array()
    }

    /** Client → server protobuf ping requesting user/version metadata. */
    fun encodeProtobuf(timestamp: Long): ByteArray =
        ProtoUdpCodec.encodePing(timestamp, requestExtended = true)

    /** Parses a server ping reply. Returns null when the datagram is not a ping. */
    fun decode(data: ByteArray): Decoded? {
        if (data.size == LEGACY_REPLY_SIZE) return decodeLegacy(data)
        if (data.isNotEmpty() && data[0] == ProtoUdpCodec.HEADER_PING.toByte()) {
            return decodeProtobuf(data)
        }
        return null
    }

    /**
     * Legacy packed version (`major<<16 | minor<<8 | patch`, 16.8.8).
     * Mirrors `Version::fromLegacyVersion` + `Version::toString`.
     */
    fun formatLegacyVersion(packed: Int): String? {
        if (packed == 0) return null
        val major = (packed ushr 16) and 0xffff
        val minor = (packed ushr 8) and 0xff
        val patch = packed and 0xff
        return "$major.$minor.$patch"
    }

    /**
     * v2 version (`major<<48 | minor<<32 | patch<<16`).
     * Mirrors `Version::toString` for `full_t`.
     */
    fun formatVersionV2(version: Long): String? {
        if (version == 0L) return null
        val major = (version ushr 48) and 0xffff
        val minor = (version ushr 32) and 0xffff
        val patch = (version ushr 16) and 0xffff
        return "$major.$minor.$patch"
    }

    private fun decodeLegacy(data: ByteArray): Decoded {
        val be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = be.int
        val timestamp = ByteBuffer.wrap(data, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
        be.position(12)
        val users = be.int
        val maxUsers = be.int
        val bandwidth = be.int
        return Decoded(
            timestamp = timestamp,
            users = users,
            maxUsers = maxUsers,
            version = formatLegacyVersion(version),
            bandwidth = bandwidth,
            // 16.8.8 packed value: patch > 255 was saturated server-side.
            versionIsV2 = false,
        )
    }

    private fun decodeProtobuf(data: ByteArray): Decoded? {
        val ping = try {
            Ping.parseFrom(data.copyOfRange(1, data.size))
        } catch (_: Exception) {
            return null
        }
        val hasExt = ping.serverVersionV2 != 0L || ping.userCount != 0 || ping.maxUserCount != 0
        return Decoded(
            timestamp = ping.timestamp,
            users = if (hasExt) ping.userCount else null,
            maxUsers = if (hasExt) ping.maxUserCount else null,
            version = formatVersionV2(ping.serverVersionV2),
            bandwidth = if (hasExt && ping.maxBandwidthPerUser != 0) ping.maxBandwidthPerUser else null,
            // Protobuf replies carry the exact v2 version (16.16.16).
            versionIsV2 = ping.serverVersionV2 != 0L,
        )
    }
}
