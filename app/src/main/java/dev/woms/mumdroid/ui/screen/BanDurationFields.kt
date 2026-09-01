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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanTimes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal enum class BanDurationPreset(
    val labelRes: Int,
    val longTerm: Boolean = false,
) {
    TEN_MINUTES(R.string.ban_preset_10m),
    ONE_HOUR(R.string.ban_preset_1h),
    TWELVE_HOURS(R.string.ban_preset_12h),
    ONE_DAY(R.string.ban_preset_1d),
    FIFTEEN_DAYS(R.string.ban_preset_15d),
    ONE_MONTH(R.string.ban_preset_1mo, longTerm = true),
    THREE_MONTHS(R.string.ban_preset_3mo, longTerm = true),
    SIX_MONTHS(R.string.ban_preset_6mo, longTerm = true),
    ONE_YEAR(R.string.ban_preset_1y, longTerm = true);

    fun secondsFrom(start: Instant): Int = when (this) {
        TEN_MINUTES -> 10 * 60
        ONE_HOUR -> 3_600
        TWELVE_HOURS -> 12 * 3_600
        ONE_DAY -> 86_400
        FIFTEEN_DAYS -> 15 * 86_400
        ONE_MONTH -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(1).toInstant(),
        )
        THREE_MONTHS -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(3).toInstant(),
        )
        SIX_MONTHS -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(6).toInstant(),
        )
        ONE_YEAR -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusYears(1).toInstant(),
        )
    }
}

internal fun durationFieldValues(seconds: Int): Triple<String, String, String> {
    val parts = BanTimes.partsFromSeconds(seconds)
    return Triple(parts.first.toString(), parts.second.toString(), parts.third.toString())
}

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
                    if (!checked && durationSecs == 0) onApplyDuration(86_400)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BanEndDatePickerDialog(
    start: Instant,
    durationSecs: Int,
    onDatePicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val minDate = start.atZone(zone).toLocalDate()
    val initialEnd = if (durationSecs > 0) {
        start.plusSeconds(durationSecs.toLong())
    } else {
        start.plus(1, ChronoUnit.DAYS)
    }
    val initialDate = initialEnd.atZone(zone).toLocalDate().let { date ->
        if (date.isBefore(minDate)) minDate else date
    }
    val selectableDates = remember(minDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !date.isBefore(minDate)
            }
        }
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = datePickerState.selectedDateMillis ?: return@TextButton
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onDatePicked(date)
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text(stringResource(R.string.ban_pick_date)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BanEndTimePickerDialog(
    start: Instant,
    date: LocalDate,
    durationSecs: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val startLocal = start.atZone(zone)
    val endLocal = if (durationSecs > 0) {
        start.plusSeconds(durationSecs.toLong()).atZone(zone)
    } else {
        start.plus(1, ChronoUnit.DAYS).atZone(zone)
    }
    val initialTime = when {
        date == endLocal.toLocalDate() -> endLocal.toLocalTime()
        date == startLocal.toLocalDate() -> startLocal.toLocalTime().plusMinutes(10)
        else -> endLocal.toLocalTime()
    }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.ban_pick_time)) },
        text = { TimePicker(state = timePickerState) },
        confirmButton = {
            Button(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
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
