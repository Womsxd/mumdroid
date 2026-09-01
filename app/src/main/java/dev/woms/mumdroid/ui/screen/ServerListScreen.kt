package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.model.pingKey

/** Displays the saved server list and an "add server" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    servers: List<MumbleServer>,
    pings: Map<String, ServerPingInfo> = emptyMap(),
    onConnect: (MumbleServer) -> Unit,
    onEdit: (MumbleServer) -> Unit,
    onDelete: (MumbleServer) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    activeServerKey: String? = null,
    activeServerId: Long? = null,
) {
    var pendingDelete by remember { mutableStateOf<MumbleServer?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_server))
            }
        },
    ) { padding ->
        val pullState = remember { InstantHidePullToRefreshState() }
        val thresholdPx = with(LocalDensity.current) {
            PullToRefreshDefaults.PositionalThreshold.toPx()
        }
        val contentOffset = pullState.distanceFraction * thresholdPx
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = contentOffset },
            ) {
                if (servers.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.no_servers_yet), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.tap_to_add_server), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(servers, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                ping = pings[server.pingKey()],
                                connected = when {
                                    activeServerId != null -> server.id == activeServerId
                                    else -> server.pingKey() == activeServerKey
                                },
                                onConnect = { onConnect(server) },
                                onEdit = { onEdit(server) },
                                onDelete = { pendingDelete = server },
                            )
                        }
                    }
                }
            }
        }
    }
    val deleteCandidate = pendingDelete
    if (deleteCandidate != null) {
        val label = deleteCandidate.name.ifEmpty { "${deleteCandidate.host}:${deleteCandidate.port}" }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_server)) },
            text = { Text(stringResource(R.string.delete_server_confirm, label)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(deleteCandidate)
                        pendingDelete = null
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
 * Same pull tracking as the default [PullToRefreshState], but hide is a snap
 * so the cards jump back with the indicator the moment refresh ends.
 */
@Stable
private class InstantHidePullToRefreshState : PullToRefreshState {
    private var distance by mutableFloatStateOf(0f)

    override val distanceFraction: Float
        get() = distance

    override val isAnimating: Boolean
        get() = false

    override suspend fun animateToHidden() {
        distance = 0f
    }

    override suspend fun animateToThreshold() {
        distance = 1f
    }

    override suspend fun snapTo(targetValue: Float) {
        distance = targetValue.coerceAtLeast(0f)
    }
}

@Composable
private fun ServerCard(
    server: MumbleServer,
    ping: ServerPingInfo?,
    connected: Boolean = false,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onConnect,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                LatencyChart(
                    ping = ping,
                    modifier = Modifier.padding(end = 14.dp, top = 6.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        server.name.ifEmpty { server.host },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.server_card_username,
                            server.username.ifEmpty { stringResource(R.string.anonymous) },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ServerPingStats(ping)
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connected) {
                    Text(
                        text = stringResource(R.string.status_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

/** Tiny 3-bar latency chart: more bars light up when the ping is healthier. */
@Composable
private fun LatencyChart(ping: ServerPingInfo?, modifier: Modifier = Modifier) {
    val color = pingStatusColor(ping)
    val lit = when {
        ping == null || ping.probing || !ping.reachable -> 0
        ping.pingMs == null -> 1
        ping.pingMs < 50 -> 3
        ping.pingMs < 150 -> 2
        else -> 1
    }
    val heights = listOf(8.dp, 12.dp, 16.dp)
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .background(
                        color = if (index < lit) color else color.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerPingStats(ping: ServerPingInfo?) {
    val style = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        ping == null || ping.probing -> {
            Text(stringResource(R.string.server_pinging), style = style, color = muted)
        }
        !ping.reachable -> {
            Text(
                stringResource(R.string.server_unreachable),
                style = style,
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val latency = ping.pingMs
                if (latency != null) {
                    Text(
                        stringResource(R.string.server_ping_ms, latency),
                        style = style,
                        color = pingLatencyColor(latency),
                    )
                }
                val users = ping.users
                val maxUsers = ping.maxUsers
                if (users != null && maxUsers != null) {
                    Text(
                        stringResource(R.string.server_users, users, maxUsers),
                        style = style,
                        color = muted,
                    )
                }
                val version = ping.version
                if (!version.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.server_version, version),
                        style = style,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun pingStatusColor(ping: ServerPingInfo?): Color {
    val latency = ping?.pingMs
    return when {
        ping == null || ping.probing -> MaterialTheme.colorScheme.onSurfaceVariant
        !ping.reachable -> MaterialTheme.colorScheme.error
        latency != null -> pingLatencyColor(latency)
        else -> MaterialTheme.colorScheme.primary
    }
}

private fun pingLatencyColor(ms: Int): Color = when {
    ms < 50 -> Color(0xFF2E7D32)
    ms < 150 -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

/** Dialog to add or edit a server. */
@Composable
fun ServerEditDialog(
    initial: MumbleServer?,
    onDismiss: () -> Unit,
    onSave: (name: String, host: String, port: Int, username: String, password: String) -> Unit,
    defaultUsername: String = "",
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 64738).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: defaultUsername) }
    var password by remember { mutableStateOf(initial?.password ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.add_server) else stringResource(R.string.edit_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text(stringResource(R.string.server_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text(stringResource(R.string.server_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.server_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.server_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = host.isNotBlank() && port.toIntOrNull() != null,
                onClick = {
                    onSave(name, host.trim(), port.toIntOrNull() ?: 64738, username.trim(), password)
                },
            ) {
                Text(stringResource(if (initial == null) R.string.add else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
