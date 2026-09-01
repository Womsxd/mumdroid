package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import kotlin.math.roundToInt

// ---- Network ----

@Composable
internal fun NetworkSettingsScreen(settings: AppSettings, onChanged: (AppSettings) -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.sec_connection))
        SwitchRow(
            title = stringResource(R.string.auto_reconnect),
            subtitle = stringResource(R.string.auto_reconnect_sub),
            checked = settings.autoReconnect,
            onCheckedChange = { onChanged(settings.copy(autoReconnect = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.certificate_pinning),
            subtitle = stringResource(R.string.certificate_pinning_sub),
            checked = settings.certificatePinning,
            onCheckedChange = { onChanged(settings.copy(certificatePinning = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.auto_server_ping),
            subtitle = stringResource(R.string.auto_server_ping_sub),
            checked = settings.autoServerPing,
            onCheckedChange = { onChanged(settings.copy(autoServerPing = it)) },
        )
        if (settings.autoServerPing) {
            ServerPingIntervalSlider(
                seconds = settings.serverPingIntervalSeconds,
                onSecondsChange = {
                    onChanged(settings.copy(serverPingIntervalSeconds = it))
                },
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_voice_transport))
        SwitchRow(
            title = stringResource(R.string.force_tcp_voice),
            subtitle = stringResource(R.string.force_tcp_sub),
            checked = settings.forceTcp,
            onCheckedChange = { onChanged(settings.copy(forceTcp = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.quality_of_service),
            subtitle = stringResource(R.string.quality_of_service_sub),
            checked = settings.qualityOfService,
            onCheckedChange = { onChanged(settings.copy(qualityOfService = it)) },
        )
    }
}

@Composable
private fun ServerPingIntervalSlider(seconds: Int, onSecondsChange: (Int) -> Unit) {
    val min = AppSettings.SERVER_PING_INTERVAL_MIN_SEC
    val max = AppSettings.SERVER_PING_INTERVAL_MAX_SEC
    val step = AppSettings.SERVER_PING_INTERVAL_STEP_SEC
    val clamped = AppSettings.clampServerPingIntervalSeconds(seconds)
    // Discrete values 5,10,…,60: 12 stops → 10 steps between the ends.
    val steps = ((max - min) / step) - 1
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.server_ping_interval, clamped),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = clamped.toFloat(),
            onValueChange = {
                onSecondsChange(AppSettings.clampServerPingIntervalSeconds(it.roundToInt()))
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
        )
    }
}
