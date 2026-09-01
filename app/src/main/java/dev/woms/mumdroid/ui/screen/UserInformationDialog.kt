package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.UserConnectionInfo

/**
 * Desktop `UserInformation` dialog: connection, ping, UDP packet stats.
 */
@Composable
fun UserInformationDialog(
    userName: String,
    info: UserConnectionInfo?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(userName.ifEmpty { stringResource(R.string.user_information) }) },
        text = {
            if (info == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.user_info_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (info.hasConnectionDetails) {
                            SectionTitle(stringResource(R.string.user_info_connection))
                            if (info.versionDisplay.isNotEmpty()) {
                                InfoRow(stringResource(R.string.user_info_version), info.versionDisplay)
                            }
                            if (info.truncatedProtocol) {
                                Text(
                                    stringResource(R.string.user_info_version_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            if (info.osDisplay.isNotEmpty()) {
                                InfoRow(stringResource(R.string.info_os), info.osDisplay)
                            }
                            if (info.certificate.isNotEmpty() || info.certificateFingerprint.isNotEmpty()) {
                                InfoRow(
                                    stringResource(R.string.user_info_certificate),
                                    info.certificate.ifEmpty { info.certificateFingerprint },
                                    emphasize = info.strongCertificate,
                                )
                                if (info.certificate.isNotEmpty() && info.certificateFingerprint.isNotEmpty()) {
                                    InfoRow(
                                        stringResource(R.string.user_info_cert_fingerprint),
                                        info.certificateFingerprint,
                                    )
                                }
                            }
                            if (info.address.isNotEmpty()) {
                                InfoRow(stringResource(R.string.user_info_address), info.address)
                            }
                            InfoRow(
                                stringResource(R.string.user_info_opus),
                                when (info.opus) {
                                    true -> stringResource(R.string.user_info_opus_supported)
                                    false -> stringResource(R.string.user_info_opus_unsupported)
                                    null -> stringResource(R.string.user_info_opus_unknown)
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        SectionTitle(stringResource(R.string.user_info_ping))
                        PingHeader()
                        PingRow(
                            stringResource(R.string.info_tcp_control),
                            info.tcpPackets,
                            UserConnectionInfo.formatPing(info.tcpPingAvg),
                            UserConnectionInfo.formatPingDeviation(info.tcpPingVar),
                        )
                        PingRow(
                            stringResource(R.string.info_udp_voice),
                            info.udpPackets,
                            UserConnectionInfo.formatPing(info.udpPingAvg),
                            UserConnectionInfo.formatPingDeviation(info.udpPingVar),
                        )

                        if (info.hasUdpStats) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            SectionTitle(stringResource(R.string.user_info_udp_stats))
                            if (info.fromClient != null || info.fromServer != null) {
                                UdpStatsBlock(
                                    fromClient = info.fromClient,
                                    fromServer = info.fromServer,
                                )
                            }
                            if (info.rollingFromClient != null || info.rollingFromServer != null) {
                                Text(
                                    rollingWindowLabel(info.rollingWindowSecs),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                                UdpStatsBlock(
                                    fromClient = info.rollingFromClient,
                                    fromServer = info.rollingFromServer,
                                )
                            }
                        }

                        if (info.onlineSecs != null || info.bandwidthBytesPerSec != null) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            SectionTitle(stringResource(R.string.user_info_session))
                            if (info.onlineSecs != null) {
                                val online = formatUserDuration(info.onlineSecs)
                                InfoRow(
                                    stringResource(R.string.user_info_time),
                                    if (info.idleSecs != null) {
                                        stringResource(
                                            R.string.user_info_online_idle,
                                            online,
                                            formatUserDuration(info.idleSecs),
                                        )
                                    } else {
                                        stringResource(R.string.user_info_online, online)
                                    },
                                )
                            }
                            if (info.bandwidthBytesPerSec != null) {
                                InfoRow(
                                    stringResource(R.string.user_info_bandwidth),
                                    UserConnectionInfo.formatBandwidth(info.bandwidthBytesPerSec),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
private fun PingHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.user_info_pings_received),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.user_info_ping_avg),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.user_info_ping_dev),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PingRow(label: String, count: Int, avg: String, deviation: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(avg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(deviation, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UdpStatsBlock(
    fromClient: UserConnectionInfo.PacketStats?,
    fromServer: UserConnectionInfo.PacketStats?,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.user_info_from_client),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.user_info_to_client),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    UdpStatRow(stringResource(R.string.info_stat_good), fromClient?.good, fromServer?.good)
    UdpStatRow(stringResource(R.string.info_stat_late), fromClient?.late, fromServer?.late)
    UdpStatRow(
        stringResource(R.string.user_info_late_percent),
        fromClient?.let { UserConnectionInfo.formatPercent(it.latePercent) },
        fromServer?.let { UserConnectionInfo.formatPercent(it.latePercent) },
    )
    UdpStatRow(stringResource(R.string.info_stat_lost), fromClient?.lost, fromServer?.lost)
    UdpStatRow(
        stringResource(R.string.user_info_lost_percent),
        fromClient?.let { UserConnectionInfo.formatPercent(it.lostPercent) },
        fromServer?.let { UserConnectionInfo.formatPercent(it.lostPercent) },
    )
    UdpStatRow(stringResource(R.string.info_stat_resync), fromClient?.resync, fromServer?.resync)
}

@Composable
private fun UdpStatRow(label: String, from: Any?, to: Any?) {
    val empty = stringResource(R.string.info_unknown)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
        Text(from?.toString() ?: empty, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(to?.toString() ?: empty, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun rollingWindowLabel(seconds: Int): String {
    return if (seconds < 120) {
        stringResource(R.string.user_info_rolling_seconds, seconds)
    } else {
        stringResource(R.string.user_info_rolling_minutes, seconds / 60)
    }
}

@Composable
private fun formatUserDuration(secs: Int): String {
    val parts = UserConnectionInfo.durationParts(secs)
    val tokens = buildList {
        if (parts.weeks > 0) add(stringResource(R.string.user_info_weeks, parts.weeks))
        if (parts.days > 0) add(stringResource(R.string.user_info_days, parts.days))
        if (parts.hours > 0) add(stringResource(R.string.user_info_hours, parts.hours))
        if (parts.minutes > 0 || parts.hours > 0) {
            add(stringResource(R.string.user_info_minutes, parts.minutes))
        }
        add(stringResource(R.string.user_info_seconds, parts.seconds))
    }
    return tokens.joinToString(" ")
}
