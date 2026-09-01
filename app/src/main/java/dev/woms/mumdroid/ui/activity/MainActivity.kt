package dev.woms.mumdroid.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.i18n.LocaleManager
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.ui.MainViewModel
import dev.woms.mumdroid.ui.screen.ServerEditDialog
import dev.woms.mumdroid.ui.screen.ServerListScreen

class MainActivity : BaseActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocaleIfNeeded(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMicrophonePermissionIfNeeded()
    }

    @Composable
    override fun Content(vm: MainViewModel) {
        val servers by vm.servers.collectAsStateWithLifecycle()
        val serverPings by vm.serverPings.collectAsStateWithLifecycle()
        val refreshingPings by vm.refreshingPings.collectAsStateWithLifecycle()
        val showAddDialog by vm.showAddDialog.collectAsStateWithLifecycle()
        val editingServer by vm.editingServer.collectAsStateWithLifecycle()
        val connectionState by vm.connectionState.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()

        // The server the user tapped; non-null shows the "connecting" dialog.
        var connectingServer by remember { mutableStateOf<MumbleServer?>(null) }

        // Startup check for chat notifications: POST_NOTIFICATIONS is a
        // runtime permission since Android 13 and can also be revoked later
        // from system settings. If the setting is on but the permission is
        // gone, ask once; if the user declines, switch the setting off — an
        // enabled toggle that can never deliver a notification only misleads.
        // The launcher callback always sees the latest `settings` snapshot.
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                vm.updateSettings(settings.copy(chatNotifications = false))
            }
        }
        LaunchedEffect(settings.chatNotifications) {
            val needsCheck = settings.chatNotifications &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needsCheck) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ServerListScreen(
            servers = servers,
            pings = serverPings,
            onConnect = { server ->
                val live = connectionState
                if (live.connected || live.connecting) {
                    startActivity(Intent(this, ConnectionActivity::class.java))
                    return@ServerListScreen
                }
                // Countdown still running: just resume the session UI.
                // If reconnecting is stuck (countdown already 0, no connect
                // in flight), fall through and send CONNECT again.
                if (live.reconnecting && live.reconnectCountdown > 0) {
                    startActivity(Intent(this, ConnectionActivity::class.java))
                    return@ServerListScreen
                }
                connectingServer = server
                vm.connectTo(server)
            },
            onEdit = { server -> vm.showAddDialog(server) },
            onDelete = { server -> vm.removeServer(server) },
            onAdd = { vm.showAddDialog() },
            onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            isRefreshing = refreshingPings,
            onRefresh = { vm.refreshPings() },
            activeServerKey = connectionState.activeServerKey,
            activeServerId = connectionState.activeServerId,
        )

        // Connecting dialog: a progress spinner while the connection is being
        // established. On success the dialog closes and the server UI opens;
        // a failure shows the concrete error — no automatic retries here.
        // (Automatic reconnect with 5/10/15 s delays only applies to drops
        // after a successful connection, handled by the service.)
        val pending = connectingServer
        if (pending != null) {
            LaunchedEffect(pending, connectionState.connected) {
                if (connectionState.connected) {
                    connectingServer = null
                    startActivity(Intent(this@MainActivity, ConnectionActivity::class.java))
                }
            }

            val failed = !connectionState.connecting && !connectionState.connected &&
                !connectionState.reconnecting && connectionState.reconnectCountdown == 0

            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.connection)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (failed) {
                            Text(
                                connectionState.status.ifEmpty {
                                    stringResource(R.string.status_not_connected)
                                },
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Text(
                                stringResource(
                                    R.string.status_connecting_to,
                                    pending.name.ifEmpty { pending.host },
                                ),
                            )
                        }
                    }
                },
                confirmButton = {
                    if (failed) {
                        Button(onClick = {
                            vm.disconnect()
                            connectingServer = null
                        }) {
                            Text(stringResource(R.string.confirm))
                        }
                    } else {
                        TextButton(onClick = {
                            vm.disconnect()
                            connectingServer = null
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
            )
        }

        if (showAddDialog) {
            ServerEditDialog(
                initial = editingServer,
                defaultUsername = vm.settings.value.defaultUsername,
                onDismiss = { vm.dismissAddDialog() },
                onSave = { name, host, port, username, password ->
                    vm.saveServer(name, host, port, username, password)
                },
            )
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
