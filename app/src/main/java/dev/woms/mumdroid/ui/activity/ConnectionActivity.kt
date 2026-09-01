package dev.woms.mumdroid.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ServerRemovalKind
import dev.woms.mumdroid.service.MumbleService
import dev.woms.mumdroid.ui.MainViewModel
import dev.woms.mumdroid.ui.screen.ConnectionScreen

/**
 * Standalone activity hosting the connection screen (status, channel tree,
 * chat). Launched when the user connects to a server from the server list.
 *
 * While the service is counting down to an automatic reconnect after an
 * unexpected drop, a prompt is shown in the foreground.
 */
class ConnectionActivity : BaseActivity() {

    private val leaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MumbleService.ACTION_SESSION_LEFT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            leaveReceiver,
            IntentFilter(MumbleService.ACTION_SESSION_LEFT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        unregisterReceiver(leaveReceiver)
        super.onDestroy()
    }

    @Composable
    override fun Content(vm: MainViewModel) {
        val state by vm.connectionState.collectAsStateWithLifecycle()
        val appSettings by vm.settings.collectAsStateWithLifecycle()

        ConnectionScreen(
            state = state,
            onBack = { finish() },
            onDisconnect = {
                vm.disconnect()
                finish()
            },
            onToggleMute = { vm.toggleSelfMute() },
            onToggleDeafen = { vm.toggleSelfDeafen() },
            onJoinChannel = { channelId -> vm.joinChannel(channelId) },
            onMoveUser = { session, channelId -> vm.moveUser(session, channelId) },
            onJoinChannelWithPassword = { channelId, token -> vm.joinChannel(channelId, token) },
            onClearChannelPasswordPrompt = { vm.clearChannelPasswordPrompt() },
            onUpdatePinnedCertificate = { vm.updatePinnedCertificate() },
            onTrustCertificateOnce = { vm.trustCertificateOnce() },
            onRejectCertificate = { vm.rejectCertificate() },
            onReplaceAccessTokens = { tokens -> vm.replaceAccessTokens(tokens) },
            canEditRegisteredUsers = { vm.canEditRegisteredUsers() },
            onRequestUserList = { vm.requestUserList() },
            onRenameRegisteredUser = { userId, name -> vm.renameRegisteredUser(userId, name) },
            onUnregisterUser = { userId -> vm.unregisterUser(userId) },
            onRefreshUserList = { vm.requestUserList(clear = false) },
            onRequestBanList = { vm.requestBanList() },
            onReplaceBanList = { bans -> vm.replaceBanList(bans) },
            onRefreshBanList = { vm.requestBanList(clear = false) },
            onSetLocalBlock = { session, blocked -> vm.setLocalBlock(session, blocked) },
            onSetLocalIgnore = { session, ignored -> vm.setLocalIgnore(session, ignored) },
            onSetRemoteMute = { session, muted -> vm.setRemoteMute(session, muted) },
            onSetRemoteDeafen = { session, deafened -> vm.setRemoteDeafen(session, deafened) },
            onSetPrioritySpeaker = { session, enabled -> vm.setPrioritySpeaker(session, enabled) },
            onKickUser = { session, reason -> vm.kickUser(session, reason) },
            onBanUser = { session, reason, banCert, banIp, duration ->
                vm.banUser(session, reason, banCert, banIp, duration)
            },
            onRegisterUser = { session -> vm.registerUser(session) },
            canAdministerChannel = { channelId -> vm.canAdministerChannel(channelId) },
            canMuteUser = { user -> vm.canMuteUser(user) },
            canPrioritySpeaker = { user -> vm.canPrioritySpeaker(user) },
            canMoveInChannel = { channelId -> vm.canMoveInChannel(channelId) },
            onQueryChannelPermissions = { channelId -> vm.ensureChannelPermissions(channelId) },
            canKickUser = { vm.canKickUser() },
            canBanUser = { vm.canBanUser() },
            canRegisterUser = { user -> vm.canRegisterUser(user) },
            supportsSelectiveBan = { vm.supportsSelectiveBan() },
            onSendChat = { channelId, text -> vm.sendChat(channelId, text) },
            onSendPrivateChat = { session, text -> vm.sendPrivateChat(session, text) },
            canTextMessage = { channelId -> vm.canTextMessage(channelId) },
            canListen = { channelId -> vm.canListen(channelId) },
            supportsChannelListen = { vm.supportsChannelListen() },
            onSetChannelListening = { channelId, listen ->
                vm.setChannelListening(channelId, listen)
            },
            canWriteChannel = { channelId -> vm.canWriteChannel(channelId) },
            canAddChannel = { channelId -> vm.canAddChannel(channelId) },
            canMakePermanentChannel = { channelId -> vm.canMakePermanentChannel(channelId) },
            canLinkChannel = { channelId -> vm.canLinkChannel(channelId) },
            onLinkChannel = { channelId -> vm.linkChannel(channelId) },
            onUnlinkChannel = { channelId -> vm.unlinkChannel(channelId) },
            onUnlinkAllChannels = { vm.unlinkAllChannels() },
            onCreateChannel = { parentId, name, description, position, temporary, maxUsers, password ->
                vm.createChannel(parentId, name, description, position, temporary, maxUsers, password)
            },
            onUpdateChannel = { channelId, name, description, position, maxUsers, password ->
                vm.updateChannel(channelId, name, description, position, maxUsers, password)
            },
            onRemoveChannel = { channelId -> vm.removeChannel(channelId) },
            onRequestChannelDescription = { channelId -> vm.requestChannelDescription(channelId) },
            onRequestChannelAcl = { channelId -> vm.requestChannelAcl(channelId) },
            showUserCount = appSettings.showUserCount,
            onTalkStart = { vm.startTalking() },
            onTalkStop = { vm.stopTalking() },
            voiceMode = appSettings.voiceMode,
            outputTarget = state.outputTarget,
            onSelectOutputTarget = { target -> vm.setOutputTarget(target) },
            onRequestUserStats = { session, statsOnly -> vm.requestUserStats(session, statsOnly) },
            onClearUserStats = { vm.clearUserStats() },
        )

        val removal = state.serverRemoval
        if (removal?.isLocal == true) {
            val title = when (removal.kind) {
                ServerRemovalKind.BANNED -> stringResource(R.string.status_banned_title)
                ServerRemovalKind.KICKED -> stringResource(R.string.status_kicked_title)
                ServerRemovalKind.REMOVED -> stringResource(R.string.status_server_removed_title)
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text(title) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(state.status.ifEmpty { title })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        vm.acknowledgeServerRemoval()
                        finish()
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
            )
        }

        // Foreground prompt while the service counts down to an automatic
        // reconnect after an unexpected drop. In the background the service
        // keeps the notification updated instead.
        if (removal == null && (state.reconnectCountdown > 0 || (state.reconnecting && !state.connected))) {
            val waiting = state.reconnectCountdown > 0
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.status_reconnecting)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            if (waiting) {
                                stringResource(R.string.reconnect_in_seconds, state.reconnectCountdown)
                            } else {
                                stringResource(R.string.status_reconnecting)
                            }
                        )
                    }
                },
                confirmButton = {
                    if (waiting) {
                        Button(onClick = { vm.reconnectNow() }) {
                            Text(stringResource(R.string.reconnect_now))
                        }
                    } else {
                        Button(onClick = {
                            vm.disconnect()
                            finish()
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
                dismissButton = {
                    if (waiting) {
                        TextButton(onClick = {
                            vm.disconnect()
                            finish()
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
            )
        }
    }
}
