package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.ServerPingInfo

@Composable
internal fun ServerCard(
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
    val lit = ServerPingDisplay.litBars(ping)
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
                        color = pingLatencyColor(ServerPingDisplay.grade(latency)),
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
private fun pingStatusColor(ping: ServerPingInfo?): Color = when (ServerPingDisplay.health(ping)) {
    PingHealth.PROBING -> MaterialTheme.colorScheme.onSurfaceVariant
    PingHealth.UNREACHABLE -> MaterialTheme.colorScheme.error
    PingHealth.NO_LATENCY -> MaterialTheme.colorScheme.primary
    PingHealth.MEASURED -> pingLatencyColor(ServerPingDisplay.grade(ping!!.pingMs!!))
}

private fun pingLatencyColor(grade: LatencyGrade): Color = when (grade) {
    LatencyGrade.FAST -> Color(0xFF2E7D32)
    LatencyGrade.MEDIUM -> Color(0xFFF9A825)
    LatencyGrade.SLOW -> Color(0xFFC62828)
}

/** Latency buckets of the server card, matching the desktop client's colours. */
internal enum class LatencyGrade { FAST, MEDIUM, SLOW }

/** Health of a ping snapshot as far as the card is concerned. */
internal enum class PingHealth { PROBING, UNREACHABLE, NO_LATENCY, MEASURED }

/**
 * Pure presentation decisions of [ServerCard]: the latency buckets, the bar
 * count and the health of a ping snapshot. Kept as plain functions (bottom of
 * this file, next to the card that consumes them) so the thresholds stay
 * unit-testable without a Compose runtime.
 */
internal object ServerPingDisplay {

    /** How many of the three latency bars light up. */
    fun litBars(ping: ServerPingInfo?): Int {
        if (ping == null || ping.probing || !ping.reachable) return 0
        val ms = ping.pingMs ?: return 1
        // FAST lights all three, each slower grade one bar less.
        return LatencyGrade.entries.size - grade(ms).ordinal
    }

    fun grade(ms: Int): LatencyGrade = when {
        ms < 50 -> LatencyGrade.FAST
        ms < 150 -> LatencyGrade.MEDIUM
        else -> LatencyGrade.SLOW
    }

    fun health(ping: ServerPingInfo?): PingHealth = when {
        ping == null || ping.probing -> PingHealth.PROBING
        !ping.reachable -> PingHealth.UNREACHABLE
        ping.pingMs == null -> PingHealth.NO_LATENCY
        else -> PingHealth.MEASURED
    }
}
