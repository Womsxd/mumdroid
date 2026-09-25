package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.proto.UserStats
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Translates a `UserStats` wire reply into the [UserConnectionInfo] the
 * user-info dialog renders, matching the desktop `UserInformation` dialog.
 */
object UserStatsCodec {

    /**
     * The connection snapshot for [msg], merged over [previous] when it is the
     * same session: a stats-only refresh carries no connection details, so the
     * previously seen ones are kept.
     */
    fun toConnectionInfo(
        msg: UserStats,
        userName: String,
        previous: UserConnectionInfo? = null,
    ): UserConnectionInfo {
        val parsed = parse(msg, userName)
        val keep = previous?.takeIf { it.session == parsed.session } ?: return parsed
        if (parsed.hasConnectionDetails) return parsed
        return parsed.copy(
            address = keep.address,
            protocol = keep.protocol,
            release = keep.release,
            os = keep.os,
            osVersion = keep.osVersion,
            certificate = keep.certificate,
            certificateFingerprint = keep.certificateFingerprint,
            strongCertificate = keep.strongCertificate,
            hasConnectionDetails = keep.hasConnectionDetails,
            truncatedProtocol = keep.truncatedProtocol,
            opus = parsed.opus ?: keep.opus,
        )
    }

    /** Formats the raw address bytes murmur sends (4 = IPv4, 16 = IPv6). */
    fun formatHostAddress(bytes: ByteArray): String {
        if (bytes.size == 4) {
            return bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
        }
        if (bytes.size != 16) return ""
        val ipv4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte()
        if (ipv4Mapped) {
            return bytes.copyOfRange(12, 16).joinToString(".") { (it.toInt() and 0xff).toString() }
        }
        val groups = (0 until 8).map { i ->
            ((bytes[i * 2].toInt() and 0xff) shl 8) or (bytes[i * 2 + 1].toInt() and 0xff)
        }
        return groups.joinToString(":") { String.format(Locale.US, "%x", it) }
    }

    private fun parse(msg: UserStats, userName: String): UserConnectionInfo {
        val hasVersion = msg.hasVersion()
        val version = if (hasVersion) msg.version else null
        val protocol = if (version != null) {
            ServerConnectionInfo.formatProtocol(version.versionV2, version.versionV1)
        } else {
            ""
        }
        val patch = when {
            version == null -> 0
            version.versionV2 != 0L -> ((version.versionV2 ushr 16) and 0xffff).toInt()
            else -> version.versionV1 and 0xff
        }
        val certs = if (msg.certificatesCount > 0) {
            parseLeafCertificate(msg.getCertificates(msg.certificatesCount - 1).toByteArray())
        } else {
            "" to ""
        }
        val address = if (msg.hasAddress()) formatHostAddress(msg.address.toByteArray()) else ""
        return UserConnectionInfo(
            session = msg.session,
            userName = userName,
            address = address,
            protocol = protocol,
            release = version?.release.orEmpty(),
            os = version?.os.orEmpty(),
            osVersion = version?.osVersion.orEmpty(),
            certificate = certs.first,
            certificateFingerprint = certs.second,
            strongCertificate = msg.strongCertificate,
            hasConnectionDetails = address.isNotEmpty() || hasVersion || msg.certificatesCount > 0,
            truncatedProtocol = patch == 255,
            opus = if (msg.hasOpus()) msg.opus else null,
            tcpPackets = msg.tcpPackets,
            udpPackets = msg.udpPackets,
            tcpPingAvg = msg.tcpPingAvg,
            tcpPingVar = msg.tcpPingVar,
            udpPingAvg = msg.udpPingAvg,
            udpPingVar = msg.udpPingVar,
            fromClient = if (msg.hasFromClient()) packetStats(msg.fromClient) else null,
            fromServer = if (msg.hasFromServer()) packetStats(msg.fromServer) else null,
            rollingFromClient = if (msg.hasRollingStats() && msg.rollingStats.hasFromClient()) {
                packetStats(msg.rollingStats.fromClient)
            } else {
                null
            },
            rollingFromServer = if (msg.hasRollingStats() && msg.rollingStats.hasFromServer()) {
                packetStats(msg.rollingStats.fromServer)
            } else {
                null
            },
            rollingWindowSecs = if (msg.hasRollingStats()) msg.rollingStats.timeWindow else 0,
            onlineSecs = if (msg.hasOnlinesecs()) msg.onlinesecs else null,
            idleSecs = if (msg.hasIdlesecs()) msg.idlesecs else null,
            bandwidthBytesPerSec = if (msg.hasBandwidth()) msg.bandwidth else null,
        )
    }

    private fun packetStats(stats: UserStats.Stats): UserConnectionInfo.PacketStats =
        UserConnectionInfo.PacketStats(stats.good, stats.late, stats.lost, stats.resync)

    private fun parseLeafCertificate(der: ByteArray): Pair<String, String> {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            val emails = cert.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                if (entry.size >= 2 && (entry[0] as? Int) == 1) entry[1] as? String else null
            }
            val identity = if (emails.isNotEmpty()) {
                emails.joinToString(", ")
            } else {
                commonName(cert.subjectX500Principal.name)
            }
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { String.format(Locale.US, "%02X", it) }
            identity to fingerprint
        } catch (_: Exception) {
            "" to ""
        }
    }

    private fun commonName(dn: String): String {
        val cn = dn.split(',').firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
        return cn?.substringAfter('=')?.trim() ?: dn
    }
}
