package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.net.ServerPingCodec
import java.util.Locale
import kotlin.math.sqrt

/**
 * Snapshot of the connected server, matching the official desktop
 * `ServerInformation` dialog (host/users/protocol, audio bandwidth, UDP/TCP).
 */
data class ServerConnectionInfo(
    val host: String = "",
    val port: Int = 0,
    val userCount: Int = 0,
    val maxUsers: Int = 0,
    val protocol: String = "",
    val release: String = "",
    val os: String = "",
    val osVersion: String = "",
    val currentBandwidthBps: Int = 0,
    val allowedBandwidthBps: Int = 0,
    val codec: String = "Opus",
    /**
     * Official `NetworkConfig::TcpModeEnabled()`: user forced TCP (or proxy).
     * The desktop dialog hides UDP stats only in this case.
     */
    val forceTcp: Boolean = false,
    /**
     * Automatic UDP→TCP fallback (`!bUdp`) while force-TCP is off.
     * Voice is tunnelled, but the desktop still shows UDP stats.
     */
    val udpFallback: Boolean = false,
    val hasUdpLatency: Boolean = false,
    val udpLatencyMs: Float = 0f,
    val udpLatencyVariance: Float = 0f,
    val udpGoodIn: Int = 0,
    val udpLateIn: Int = 0,
    val udpLostIn: Int = 0,
    val udpResyncIn: Int = 0,
    val udpGoodOut: Int = 0,
    val udpLateOut: Int = 0,
    val udpLostOut: Int = 0,
    val udpResyncOut: Int = 0,
    val tlsVersion: String = "",
    val cipherSuite: String = "",
    val hasTcpLatency: Boolean = false,
    val tcpLatencyMs: Float = 0f,
    val tcpLatencyVariance: Float = 0f,
    val perfectForwardSecrecy: Boolean? = null,
    val certificateFingerprint: String = "",
) {
    val osDisplay: String
        get() = when {
            os.isEmpty() -> ""
            osVersion.isEmpty() -> os
            else -> "$os ($osVersion)"
        }

    companion object {
        fun formatProtocol(versionV2: Long, legacy: Int): String =
            ServerPingCodec.formatVersionV2(versionV2)
                ?: ServerPingCodec.formatLegacyVersion(legacy)
                ?: ""

        /** Maps Android `SSLSession.protocol` (`TLSv1.3`) to the desktop `TLS 1.3` label. */
        fun formatTlsProtocol(raw: String): String {
            val n = raw.trim()
            return when {
                n.equals("TLSv1.3", ignoreCase = true) || n.equals("TLS1.3", ignoreCase = true) -> "TLS 1.3"
                n.equals("TLSv1.2", ignoreCase = true) || n.equals("TLS1.2", ignoreCase = true) -> "TLS 1.2"
                n.equals("TLSv1.1", ignoreCase = true) || n.equals("TLS1.1", ignoreCase = true) -> "TLS 1.1"
                n.equals("TLSv1", ignoreCase = true) || n.equals("TLSv1.0", ignoreCase = true) -> "TLS 1.0"
                else -> n
            }
        }

        /**
         * TLS 1.3 is always PFS. For 1.2, DHE/ECDHE in the suite name is the
         * practical equivalent of the desktop `ephemeralServerKey()` check.
         */
        fun usesPerfectForwardSecrecy(protocol: String, cipher: String): Boolean {
            val p = protocol.uppercase(Locale.US)
            if (p.contains("1.3")) return true
            val c = cipher.uppercase(Locale.US)
            return c.contains("ECDHE") || c.contains("DHE")
        }

        /** Official `ServerInformation`: `"%1$.1f ms (σ = %2$.1f ms)"`. */
        fun formatLatency(avgMs: Float, variance: Float): String {
            val sigma = sqrt(variance.toDouble().coerceAtLeast(0.0))
            return String.format(Locale.US, "%.1f ms (σ = %.1f ms)", avgMs, sigma)
        }

        /** Official bandwidth labels are kBit/s with one decimal. */
        fun formatBandwidth(bps: Int): String =
            String.format(Locale.US, "%.1f kBit/s", bps / 1000.0f)
    }
}
