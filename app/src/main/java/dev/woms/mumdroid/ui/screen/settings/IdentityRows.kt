package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.woms.mumdroid.data.db.CertificateEntity

/**
 * The plain rows of the identity settings page: the default-username summary
 * row (with its edit dialog) and the read-only row of a known server
 * certificate.
 */

/**
 * The default username setting shown as a summary row: the label on the left
 * and the current value on the right. Tapping it opens a dialog in which the
 * username can be edited and saved or the change cancelled.
 */
@Composable
internal fun DefaultUsernameRow(value: String, onValueChange: (String) -> Unit) {
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

/** A remembered server certificate, listed with its host and fingerprint. */
@Composable
internal fun CertificateRow(certificate: CertificateEntity, onDelete: () -> Unit) {
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
