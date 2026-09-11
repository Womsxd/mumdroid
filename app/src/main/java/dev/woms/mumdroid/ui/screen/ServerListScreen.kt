package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.BuildConfig
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name))
                        if (BuildConfig.DEBUG) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.error,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.debug_build),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            }
                        }
                    }
                },
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
internal class InstantHidePullToRefreshState : PullToRefreshState {
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
