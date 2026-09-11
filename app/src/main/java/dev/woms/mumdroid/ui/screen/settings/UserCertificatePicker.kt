package dev.woms.mumdroid.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.isPresent

/**
 * A second-level screen listing every stored user certificate. The user can tap
 * a row to make it active, export or delete a certificate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserCertificatePicker(
    userCertificate: UserCertificate,
    userCertificates: List<UserCertificate>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmation by remember { mutableStateOf(DeleteConfirmation<UserCertificate>()) }
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
            contentPadding = PaddingValues(
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
                    onDelete = { confirmation = confirmation.request(cert) },
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
    confirmation.candidate?.let { deleteCandidate ->
        AlertDialog(
            onDismissRequest = { confirmation = confirmation.dismiss() },
            title = { Text(stringResource(R.string.delete_certificate)) },
            text = {
                Text(
                    stringResource(
                        if (confirmation.requiresSecondStep) {
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
                        val (next, toDelete) = confirmation.advance()
                        confirmation = next
                        if (toDelete != null) onDelete(toDelete.fingerprint)
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
                TextButton(onClick = { confirmation = confirmation.dismiss() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
