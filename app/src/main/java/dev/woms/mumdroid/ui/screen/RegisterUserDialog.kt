package dev.woms.mumdroid.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R

/**
 * Desktop `QMessageBox` before `ServerHandler::registerUser`. Self-register
 * and admin-register use different titles and warnings.
 */
@Composable
fun RegisterUserDialog(
    userName: String,
    isSelf: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isSelf) R.string.register_self_title else R.string.register_other_title,
                    userName,
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (isSelf) R.string.register_self_message else R.string.register_other_message,
                    userName,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.register_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
