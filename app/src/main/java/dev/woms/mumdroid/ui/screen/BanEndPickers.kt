package dev.woms.mumdroid.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.woms.mumdroid.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The two-step "pick an end date, then a time" flow of a custom ban duration.
 * Keeping the date/time maths here leaves [BanDurationFields] a plain form.
 */

/** First step: which calendar date the ban ends. Dates before the start are unselectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BanEndDatePickerDialog(
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
        // Nothing set yet: default the picker one day ahead.
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

/** Second step: the wall-clock time on the picked date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BanEndTimePickerDialog(
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
        // Same day as the start: never pre-fill a time before it.
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
