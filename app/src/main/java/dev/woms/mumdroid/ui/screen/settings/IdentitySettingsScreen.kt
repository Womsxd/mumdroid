package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.isPresent
import dev.woms.mumdroid.data.db.CertificateEntity

// ---- Certificates (Identity & Certificates) ----

/**
 * The identity settings page: the default username plus the user (client)
 * certificate section and the list of remembered server certificates. The
 * certificate picker is a separate screen (see [UserCertificatePicker]); rows
 * and cards live in [IdentityRows] / [UserCertificateCards].
 */
@Composable
internal fun IdentitySettingsScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    certificates: List<CertificateEntity>,
    userCertificate: UserCertificate,
    userCertificates: List<UserCertificate>,
    userCertificateError: String?,
    onClearUserCertificateError: () -> Unit,
    onDeleteCertificate: (CertificateEntity) -> Unit,
    onGenerateUserCertificate: (String) -> Unit,
    onDeleteUserCertificate: (String) -> Unit,
    onSelectUserCertificate: (String) -> Unit,
    onImportUserCertificate: () -> Unit,
    onExportUserCertificate: (String) -> Unit,
    onBackupUserCertificateChange: (Boolean) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier,
) {
    var showGenerateDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp,
        ),
    ) {
        // ---- Identity section (merged from the former Account page) ----
        item {
            SectionHeader(stringResource(R.string.sec_identity))
            DefaultUsernameRow(
                value = settings.defaultUsername,
                onValueChange = { onSettingsChanged(settings.copy(defaultUsername = it)) },
            )
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        // ---- User (client) certificate section ----
        item {
            SectionHeader(stringResource(R.string.user_certificate))
        }
        if (!userCertificate.isPresent()) {
            item {
                Text(
                    stringResource(R.string.user_certificate_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            // The settings page shows a single card for the active certificate.
            // Tapping it opens the certificate picker where the user can switch
            // between, export or delete certificates.
            item {
                UserCertificateSummaryCard(
                    active = userCertificate,
                    count = userCertificates.size,
                    onClick = onOpenPicker,
                )
            }
        }

        // Whether the private key may leave the device through cloud backup.
        item {
            SwitchRow(
                title = stringResource(R.string.backup_user_certificate),
                subtitle = stringResource(R.string.backup_user_certificate_sub),
                checked = settings.backupUserCertificates,
                onCheckedChange = onBackupUserCertificateChange,
            )
        }

        // Generate / import user certificates. Export lives inside the picker,
        // attached to each individual certificate.
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showGenerateDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.generate_certificate))
                }
                OutlinedButton(
                    onClick = onImportUserCertificate,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.import_certificate))
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.server_certificates))
        }
        if (certificates.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_certificates_yet),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.certificates_recorded_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(certificates, key = { it.id }) { cert ->
                CertificateRow(cert, onDelete = { onDeleteCertificate(cert) })
                HorizontalDivider()
            }
        }
    }

    // Certificate picker is hosted by the identity category activity as an
    // exclusive branch, so back navigation from it always returns to this page.

    // Dialog for generating a new self-signed certificate with the given identity.
    if (showGenerateDialog) {
        var subject by remember {
            mutableStateOf(settings.defaultUsername.trim().ifEmpty { "mumdroid-user" })
        }
        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text(stringResource(R.string.generate_certificate)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.user_certificate_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(stringResource(R.string.certificate_identity)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showGenerateDialog = false
                    onGenerateUserCertificate(subject)
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Generation failure: a dialog mirroring the import-error feedback, so
    // the user learns the tap did nothing instead of wondering why no
    // certificate appeared.
    userCertificateError?.let { message ->
        AlertDialog(
            onDismissRequest = onClearUserCertificateError,
            title = { Text(stringResource(R.string.generate_certificate)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearUserCertificateError) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}
