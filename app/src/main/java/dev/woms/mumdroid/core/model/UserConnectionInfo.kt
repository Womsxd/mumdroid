package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.model.UserConnectionInfo.Companion.formatBandwidth
import java.util.Locale
import kotlin.math.sqrt

/**
 * Snapshot of another user's connection, matching the desktop
 * `UserInformation` dialog filled from `UserStats`.
 *
 * Pure model plus the formatting the dialog renders with; the wire
 * translation from `UserStats` lives in `core.net.UserStatsCodec`.
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
    }
}
