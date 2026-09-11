package dev.woms.mumdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * the bar, the tabs and the two panels.
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
