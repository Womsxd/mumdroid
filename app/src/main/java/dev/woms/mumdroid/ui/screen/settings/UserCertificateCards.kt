package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.ui.screen.settings.CertificateFormatting.displaySubject
import dev.woms.mumdroid.ui.screen.settings.CertificateFormatting.formatEpoch

/**
 * Cards for the user (client) certificate section: the summary card on the
 * settings page and the selectable rows of the certificate picker.
 */

/**
 * A single summary card shown on the certificate settings page for the active
 * (in-use) user certificate. Tapping it opens the certificate picker where all
 * stored certificates can be managed.
 */
@Composable
internal fun UserCertificateSummaryCard(
    active: UserCertificate,
    count: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displaySubject(active.subject).ifBlank { stringResource(R.string.user_certificate) },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.cert_in_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            CertificateDetail(stringResource(R.string.cert_fingerprint), active.fingerprint)
            if (count > 1) {
                Text(
                    stringResource(R.string.cert_count_hint, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A single user (client) certificate row. The active certificate is highlighted
 * and tapping a row selects it for use during the TLS handshake. Each row has
 * its own export and delete actions.
 */
@Composable
internal fun UserCertificateRow(
    certificate: UserCertificate,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f).clickable(onClick = onSelect)) {
                    Text(
                        displaySubject(certificate.subject),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (selected) stringResource(R.string.cert_in_use) else stringResource(R.string.cert_tap_to_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.export_certificate))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_certificate),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            CertificateDetail(stringResource(R.string.cert_fingerprint), certificate.fingerprint)
            CertificateDetail(stringResource(R.string.cert_serial), certificate.serial)
            CertificateDetail(
                stringResource(R.string.cert_validity),
                stringResource(
                    R.string.cert_validity_range,
                    formatEpoch(certificate.notBefore),
                    formatEpoch(certificate.notAfter),
                ),
            )
        }
    }
}

/** A label/value pair shown inside a certificate card. */
@Composable
private fun CertificateDetail(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
