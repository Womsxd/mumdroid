package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.CertificatePrompt
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.ui.ConnectionState
import dev.woms.mumdroid.ui.SessionCommands
import kotlinx.coroutines.delay

/**
 * The dialogs the connection screen can stack over the channel tree: the channel
 * password prompt, the certificate-mismatch prompt, the server information and
 * access-token sheets, the user information dialog (with its refresh loop), and
 * the whisper / shout pickers reached from the local user's row.
 *
 * They are all "same-shaped" — a nullable piece of [ConnectionScreenState] plus
 * the command that resolves it — so grouping them keeps the screen composable to
 * the bar, the tabs and the two panels. The password prompt and the certificate
 * prompt are the two that have no other caller, so they are defined at the
 * bottom of this file.
 */
@Composable
internal fun ConnectionScreenDialogs(
    state: ConnectionState,
    commands: SessionCommands,
    screen: ConnectionScreenState,
    moveChannels: List<ChannelPick>,
) {
    val passwordPrompt = state.channelPasswordPrompt ?: screen.localPasswordPrompt
    if (passwordPrompt != null) {
        ChannelPasswordDialog(
            prompt = passwordPrompt,
            onSubmit = { token ->
                screen.localPasswordPrompt = null
                commands.joinChannel(passwordPrompt.channelId, token)
            },
            onDismiss = {
                screen.localPasswordPrompt = null
                commands.clearChannelPasswordPrompt()
            },
        )
    }

    // Certificate pinning mismatch: the TLS handshake is paused until the
    // user updates the pin, trusts the certificate once, or rejects.
    state.certificatePrompt?.let { prompt ->
        CertificatePromptDialog(
            prompt = prompt,
            onUpdatePin = commands::updatePinnedCertificate,
            onTrustOnce = commands::trustCertificateOnce,
            onReject = commands::rejectCertificate,
        )
    }

    if (screen.showServerInfo && state.connected) {
        ServerInformationDialog(
            info = state.serverInfo,
            onDismiss = { screen.showServerInfo = false },
        )
    }

    if (screen.showAccessTokens && state.connected) {
        AccessTokensDialog(
            tokens = state.accessTokens,
            onReplace = commands::replaceAccessTokens,
            onDismiss = { screen.showAccessTokens = false },
        )
    }

    val viewingSession = screen.infoSession
    if (viewingSession != null) {
        // User statistics change server-side; poll while the dialog is open.
        LaunchedEffect(viewingSession) {
            while (true) {
                delay(6_000)
                commands.requestUserStats(viewingSession, true)
            }
        }
        UserInformationDialog(
            userName = screen.infoUserName,
            info = state.userInfo?.takeIf { it.session == viewingSession },
            onDismiss = {
                screen.closeUserInformation()
                commands.clearUserStats()
            },
        )
    }

    // Whisper / shout pickers, reached from the local user's long-press menu.
    if (screen.whisperPicker) {
        WhisperToUsersDialog(
            users = state.users,
            onConfirm = { sessions ->
                screen.whisperPicker = false
                if (sessions.isNotEmpty()) commands.setVoiceTarget(VoiceTargetSpec.Users(sessions))
            },
            onDismiss = { screen.whisperPicker = false },
        )
    }
    if (screen.shoutPicker) {
        ShoutChannelPickerDialog(
            channels = moveChannels,
            onSelect = { pick ->
                screen.shoutPicker = false
                screen.pendingShoutChannel = pick
            },
            onDismiss = { screen.shoutPicker = false },
        )
    }
    screen.pendingShoutChannel?.let { pick ->
        ShoutToChannelDialog(
            channelName = pick.name,
            onConfirm = { links, children, group ->
                screen.pendingShoutChannel = null
                commands.setVoiceTarget(VoiceTargetSpec.Channel(pick.id, links, children, group))
            },
            onDismiss = { screen.pendingShoutChannel = null },
        )
    }
}

@Composable
fun ChannelPasswordDialog(
    prompt: ChannelPasswordPrompt,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(prompt.channelId, prompt.retry) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt.channelId) { focus.requestFocus() }

    fun submit() {
        val token = password.trim()
        if (token.isEmpty()) return
        onSubmit(token)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_password_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.channel_password_message, prompt.channelName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (prompt.retry) {
                    Text(
                        stringResource(R.string.channel_password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.channel_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .focusRequester(focus),
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = password.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

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
