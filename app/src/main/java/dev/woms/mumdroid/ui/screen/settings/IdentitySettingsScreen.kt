package dev.woms.mumdroid.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.isPresent
import dev.woms.mumdroid.data.db.CertificateEntity

// ---- Certificates (Identity & Certificates) ----

@Composable
internal fun IdentitySettingsScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    certificates: List<CertificateEntity>,
    userCertificate: dev.woms.mumdroid.core.model.UserCertificate,
    userCertificates: List<dev.woms.mumdroid.core.model.UserCertificate>,
    userCertificateError: String?,
    onClearUserCertificateError: () -> Unit,
    onDeleteCertificate: (CertificateEntity) -> Unit,
    onGenerateUserCertificate: (String) -> Unit,
    onDeleteUserCertificate: (String) -> Unit,
    onSelectUserCertificate: (String) -> Unit,
    onImportUserCertificate: () -> Unit,
    onExportUserCertificate: (String) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier,
) {
    var showGenerateDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
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

/**
 * A single summary card shown on the certificate settings page for the active
 * (in-use) user certificate. Tapping it opens the [UserCertificatePicker] where
 * all stored certificates can be managed.
 */
@Composable
private fun UserCertificateSummaryCard(
    active: dev.woms.mumdroid.core.model.UserCertificate,
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
                        active.subject.removePrefix("CN=").ifBlank { stringResource(R.string.user_certificate) },
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
 * A second-level screen listing every stored user certificate. The user can tap
 * a row to make it active, export or delete a certificate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserCertificatePicker(
    userCertificate: dev.woms.mumdroid.core.model.UserCertificate,
    userCertificates: List<dev.woms.mumdroid.core.model.UserCertificate>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // The certificate pending deletion; non-null shows the confirmation dialog.
    var pendingDelete by remember { mutableStateOf<dev.woms.mumdroid.core.model.UserCertificate?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.user_certificate)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
        ) {
            items(userCertificates, key = { it.fingerprint }) { cert ->
                UserCertificateRow(
                    certificate = cert,
                    selected = cert.fingerprint == userCertificate.fingerprint && userCertificate.isPresent(),
                    onSelect = { onSelect(cert.fingerprint) },
                    onExport = { onExport(cert.fingerprint) },
                    onDelete = { pendingDelete = cert },
                )
            }
            if (userCertificates.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.user_certificate_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    // Two-step confirmation before a certificate is irreversibly deleted.
    val deleteCandidate = pendingDelete
    if (deleteCandidate != null) {
        var secondStep by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_certificate)) },
            text = {
                Text(
                    stringResource(
                        if (secondStep) {
                            R.string.delete_certificate_confirm_again
                        } else {
                            R.string.delete_certificate_confirm
                        },
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (secondStep) {
                            onDelete(deleteCandidate.fingerprint)
                            pendingDelete = null
                        } else {
                            secondStep = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * A single user (client) certificate row. The active certificate is highlighted
 * and tapping a row selects it for use during the TLS handshake. Each row has
 * its own export and delete actions.
 */
@Composable
private fun UserCertificateRow(
    certificate: dev.woms.mumdroid.core.model.UserCertificate,
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
                        certificate.subject.removePrefix("CN="),
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

private fun formatEpoch(epochMillis: Long): String {
    if (epochMillis <= 0) return "-"
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    return fmt.format(java.util.Date(epochMillis))
}

@Composable
private fun CertificateRow(certificate: CertificateEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text("${certificate.host}:${certificate.port}", style = MaterialTheme.typography.titleSmall)
            Text(
                certificate.fingerprint.ifEmpty { "SHA-256" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.delete))
        }
    }
}

/**
 * The default username setting shown as a summary row: the label on the left
 * and the current value on the right. Tapping it opens a dialog in which the
 * username can be edited and saved or the change cancelled.
 */
@Composable
private fun DefaultUsernameRow(value: String, onValueChange: (String) -> Unit) {
    var showEditDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = { showEditDialog = true }).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.default_username),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showEditDialog) {
        var fieldValue by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.default_username)) },
            text = {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    label = { Text(stringResource(R.string.default_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    showEditDialog = false
                    onValueChange(fieldValue.trim())
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

