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
            commands = vm.sessionCommands,
            onBack = { finish() },
            onDisconnect = {
                vm.disconnect()
                finish()
            },
            showUserCount = appSettings.showUserCount,
            voiceMode = appSettings.voiceMode,
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
