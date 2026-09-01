package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.CertificatePrompt

/**
 * Raised while certificate pinning is enabled and the server presented a
 * certificate whose fingerprint does not match the pinned one. The TLS
 * handshake is paused until the user updates the pinned certificate, trusts
 * it for this session only, or rejects the connection.
 */
@Composable
fun CertificatePromptDialog(
    prompt: CertificatePrompt,
    onUpdatePin: () -> Unit,
    onTrustOnce: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.certificate_prompt_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.certificate_prompt_message,
                        "${prompt.host}:${prompt.port}",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.certificate_prompt_pinned, prompt.pinnedFingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.certificate_prompt_presented, prompt.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.certificate_prompt_question),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onTrustOnce) {
                    Text(stringResource(R.string.certificate_trust_once))
                }
                Button(onClick = onUpdatePin) {
                    Text(stringResource(R.string.certificate_update))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.certificate_reject))
            }
        },
    )
}
