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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ServerConnectionInfo

/**
 * Connection-screen counterpart of the desktop `ServerInformation` dialog.
 */
@Composable
fun ServerInformationDialog(
    info: ServerConnectionInfo,
    onDismiss: () -> Unit,
) {
    val unknown = stringResource(R.string.info_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_information)) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SectionTitle(stringResource(R.string.server_information))
                    InfoRow(stringResource(R.string.info_host), info.host.ifEmpty { unknown })
                    InfoRow(
                        stringResource(R.string.info_port),
                        if (info.port > 0) info.port.toString() else unknown,
                    )
                    InfoRow(
                        stringResource(R.string.info_users),
                        if (info.maxUsers > 0) {
                            "${info.userCount} / ${info.maxUsers}"
                        } else {
                            "${info.userCount} / $unknown"
                        },
                    )
                    InfoRow(stringResource(R.string.info_protocol), info.protocol.ifEmpty { unknown })
                    InfoRow(stringResource(R.string.info_release), info.release.ifEmpty { unknown })
                    InfoRow(stringResource(R.string.info_os), info.osDisplay.ifEmpty { unknown })

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionTitle(stringResource(R.string.info_audio))
                    InfoRow(
                        stringResource(R.string.info_audio_current),
                        ServerConnectionInfo.formatBandwidth(info.currentBandwidthBps),
                    )
                    InfoRow(
                        stringResource(R.string.info_audio_allowed),
                        if (info.allowedBandwidthBps > 0) {
                            ServerConnectionInfo.formatBandwidth(info.allowedBandwidthBps)
                        } else {
                            unknown
                        },
                    )
                    InfoRow(stringResource(R.string.info_audio_codec), info.codec)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionTitle(stringResource(R.string.info_udp_voice))
                    if (info.forceTcp) {
                        Text(
                            stringResource(R.string.info_voice_over_tcp),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        if (info.udpFallback) {
                            Text(
                                stringResource(R.string.info_voice_tcp_fallback),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        InfoRow(
                            stringResource(R.string.info_encryption),
                            stringResource(R.string.info_udp_encryption),
                        )
                        InfoRow(
                            stringResource(R.string.info_avg_latency),
                            if (info.hasUdpLatency) {
                                ServerConnectionInfo.formatLatency(
                                    info.udpLatencyMs,
                                    info.udpLatencyVariance,
                                )
                            } else {
                                unknown
                            },
                        )
                        Text(
                            stringResource(R.string.info_statistics),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        StatsHeader()
                        StatsRow(stringResource(R.string.info_stat_good), info.udpGoodOut, info.udpGoodIn)
                        StatsRow(stringResource(R.string.info_stat_late), info.udpLateOut, info.udpLateIn)
                        StatsRow(stringResource(R.string.info_stat_lost), info.udpLostOut, info.udpLostIn)
                        StatsRow(stringResource(R.string.info_stat_resync), info.udpResyncOut, info.udpResyncIn)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionTitle(stringResource(R.string.info_tcp_control))
                    InfoRow(stringResource(R.string.info_tls_version), info.tlsVersion.ifEmpty { unknown })
                    InfoRow(stringResource(R.string.info_cipher_suite), info.cipherSuite.ifEmpty { unknown })
                    InfoRow(
                        stringResource(R.string.info_avg_latency),
                        if (info.hasTcpLatency) {
                            ServerConnectionInfo.formatLatency(
                                info.tcpLatencyMs,
                                info.tcpLatencyVariance,
                            )
                        } else {
                            unknown
                        },
                    )
                    InfoRow(
                        stringResource(R.string.info_forward_secrecy),
                        when (info.perfectForwardSecrecy) {
                            true -> stringResource(R.string.info_yes)
                            false -> stringResource(R.string.info_no)
                            null -> unknown
                        },
                    )
                    InfoRow(
                        stringResource(R.string.info_certificate_fingerprint),
                        info.certificateFingerprint.ifEmpty { unknown },
                    )
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
private fun InfoRow(label: String, value: String) {
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
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
private fun StatsHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.info_stat_outgoing),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.info_stat_incoming),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatsRow(label: String, outgoing: Int, incoming: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(outgoing.toString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(incoming.toString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
