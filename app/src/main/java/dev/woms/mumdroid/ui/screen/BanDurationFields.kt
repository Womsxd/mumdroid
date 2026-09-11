package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanTimes
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun BanDurationFields(
    startInstant: Instant,
    permanent: Boolean,
    onPermanentChange: (Boolean) -> Unit,
    days: String,
    hours: String,
    minutes: String,
    onDaysChange: (String) -> Unit,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    durationSecs: Int,
    endTooEarly: Boolean,
    onApplyDuration: (Int) -> Unit,
    onEndTooEarly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val startLabel = BanTimes.formatCompact(startInstant)
    val endLabel = if (permanent) {
        stringResource(R.string.ban_permanent)
    } else {
        BanTimes.formatCompact(startInstant.plusSeconds(durationSecs.toLong()))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ban_permanent_switch),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = permanent,
                onCheckedChange = { checked ->
                    onPermanentChange(checked)
                    if (!checked && durationSecs == 0) onApplyDuration(DEFAULT_BAN_DURATION_SECONDS)
                },
            )
        }
        BanPeriodRow(
            startLabel = startLabel,
            endLabel = endLabel,
            pickEnabled = !permanent,
            onPickEnd = { showDatePicker = true },
        )
        if (!permanent) {
            BanDurationChips(
                presets = BanDurationPreset.entries.filter { !it.longTerm },
                start = startInstant,
                durationSecs = durationSecs,
                onSelect = onApplyDuration,
            )
            BanDurationChips(
                presets = BanDurationPreset.entries.filter { it.longTerm },
                start = startInstant,
                durationSecs = durationSecs,
                onSelect = onApplyDuration,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DurationField(
                    value = days,
                    onValueChange = onDaysChange,
                    label = stringResource(R.string.ban_duration_days),
                    modifier = Modifier.weight(1f),
                )
                DurationField(
                    value = hours,
                    onValueChange = onHoursChange,
                    label = stringResource(R.string.ban_duration_hours),
                    modifier = Modifier.weight(1f),
                )
                DurationField(
                    value = minutes,
                    onValueChange = onMinutesChange,
                    label = stringResource(R.string.ban_duration_minutes),
                    modifier = Modifier.weight(1f),
                )
            }
            if (durationSecs <= 0 || endTooEarly) {
                Text(
                    stringResource(
                        if (endTooEarly) {
                            R.string.ban_end_not_after_start
                        } else {
                            R.string.ban_duration_required
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDatePicker) {
        BanEndDatePickerDialog(
            start = startInstant,
            durationSecs = durationSecs,
            onDatePicked = { date ->
                pickedDate = date
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    val dateForTime = pickedDate
    if (dateForTime != null) {
        BanEndTimePickerDialog(
            start = startInstant,
            date = dateForTime,
            durationSecs = durationSecs,
            onConfirm = { hour, minute ->
                val end = BanTimes.ofLocal(dateForTime, hour, minute)
                val seconds = BanTimes.secondsBetween(startInstant, end)
                if (seconds <= 0) onEndTooEarly() else onApplyDuration(seconds)
                pickedDate = null
            },
            onDismiss = { pickedDate = null },
        )
    }
}

@Composable
private fun BanPeriodRow(
    startLabel: String,
    endLabel: String,
    pickEnabled: Boolean,
    onPickEnd: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ban_start),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Text(startLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (pickEnabled) Modifier.clickable(onClick = onPickEnd) else Modifier,
                ),
        ) {
            Text(
                stringResource(R.string.ban_end),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Text(endLabel, style = MaterialTheme.typography.bodyMedium)
        }
        if (pickEnabled) {
            IconButton(onClick = onPickEnd) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = stringResource(R.string.ban_pick_end),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BanDurationChips(
    presets: List<BanDurationPreset>,
    start: Instant,
    durationSecs: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        presets.forEach { preset ->
            val seconds = preset.secondsFrom(start)
            FilterChip(
                selected = durationSecs == seconds && seconds > 0,
                onClick = { onSelect(seconds) },
                label = { Text(stringResource(preset.labelRes)) },
            )
        }
    }
}

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

