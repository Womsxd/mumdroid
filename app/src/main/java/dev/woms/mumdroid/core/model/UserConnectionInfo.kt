package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.UserStats
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.math.sqrt

/**
 * Snapshot of another user's connection, matching the desktop
 * `UserInformation` dialog filled from `UserStats`.
 */
data class UserConnectionInfo(
    val session: Int = 0,
    val userName: String = "",
    val address: String = "",
    val protocol: String = "",
    val release: String = "",
    val os: String = "",
    val osVersion: String = "",
    val certificate: String = "",
    val certificateFingerprint: String = "",
    val strongCertificate: Boolean = false,
    val hasConnectionDetails: Boolean = false,
    val truncatedProtocol: Boolean = false,
    /** Null when the server omitted the Opus field (`Not Reported`). */
    val opus: Boolean? = null,
    val tcpPackets: Int = 0,
    val udpPackets: Int = 0,
    val tcpPingAvg: Float = 0f,
    val tcpPingVar: Float = 0f,
    val udpPingAvg: Float = 0f,
    val udpPingVar: Float = 0f,
    val fromClient: PacketStats? = null,
    val fromServer: PacketStats? = null,
    val rollingFromClient: PacketStats? = null,
    val rollingFromServer: PacketStats? = null,
    val rollingWindowSecs: Int = 0,
    val onlineSecs: Int? = null,
    val idleSecs: Int? = null,
    /** Bytes/s from the server; display as kbit/s via [formatBandwidth]. */
    val bandwidthBytesPerSec: Int? = null,
) {
    val osDisplay: String
        get() = when {
            os.isEmpty() -> ""
            osVersion.isEmpty() -> os
            else -> "$os ($osVersion)"
        }

    val versionDisplay: String
        get() = when {
            protocol.isEmpty() && release.isEmpty() -> ""
            protocol.isEmpty() -> release
            release.isEmpty() -> protocol
            else -> "$protocol ($release)"
        }

    val hasUdpStats: Boolean
        get() = fromClient != null || fromServer != null ||
            rollingFromClient != null || rollingFromServer != null

    data class PacketStats(
        val good: Int = 0,
        val late: Int = 0,
        val lost: Int = 0,
        val resync: Int = 0,
    ) {
        val counted: Int get() = good + late + lost
        val latePercent: Double get() = if (counted > 0) late * 100.0 / counted else 0.0
        val lostPercent: Double get() = if (counted > 0) lost * 100.0 / counted else 0.0
    }

    data class DurationParts(
        val weeks: Int,
        val days: Int,
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
    )

    companion object {
        fun fromProto(msg: UserStats, userName: String, previous: UserConnectionInfo? = null): UserConnectionInfo {
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

        fun durationParts(secs: Int): DurationParts {
            var remain = secs.coerceAtLeast(0)
            val weeks = remain / (60 * 60 * 24 * 7)
            remain -= weeks * 60 * 60 * 24 * 7
            val days = remain / (60 * 60 * 24)
            remain -= days * 60 * 60 * 24
            val hours = remain / (60 * 60)
            remain -= hours * 60 * 60
            val minutes = remain / 60
            val seconds = remain - minutes * 60
            return DurationParts(weeks, days, hours, minutes, seconds)
        }

        /** Official `UserInformation`: `bandwidth / 125.0` → kbit/s. */
        fun formatBandwidth(bytesPerSec: Int): String =
            String.format(Locale.US, "%.1f kbit/s", bytesPerSec / 125.0)

        fun formatPing(value: Float): String =
            String.format(Locale.US, "%.2f", value)

        fun formatPingDeviation(variance: Float): String =
            String.format(Locale.US, "%.2f", sqrt(variance.toDouble().coerceAtLeast(0.0)))

        fun formatPercent(value: Double): String =
            String.format(Locale.US, "%.1f%%", value)

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

        private fun packetStats(stats: UserStats.Stats): PacketStats =
            PacketStats(stats.good, stats.late, stats.lost, stats.resync)

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
}
