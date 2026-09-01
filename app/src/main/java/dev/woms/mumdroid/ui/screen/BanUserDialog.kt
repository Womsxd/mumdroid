package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanTimes
import dev.woms.mumdroid.core.model.UserModeration
import java.time.Instant

/** Desktop `BanDialog`: reason, optional certificate/IP, plus timed duration. */
@Composable
fun BanUserDialog(
    userName: String,
    hasCertificate: Boolean,
    showBanOptions: Boolean,
    onConfirm: (reason: String, banCertificate: Boolean, banIp: Boolean, duration: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(showBanOptions, hasCertificate) {
        UserModeration.initialBanOptions(showBanOptions, hasCertificate)
    }
    var reason by remember { mutableStateOf("") }
    var banCertificate by remember { mutableStateOf(initial.banCertificate) }
    var banIp by remember { mutableStateOf(initial.banIp) }
    var permanent by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf("1") }
    var hours by remember { mutableStateOf("0") }
    var minutes by remember { mutableStateOf("0") }
    var endTooEarly by remember { mutableStateOf(false) }
    val startInstant = remember { Instant.now() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(userName) { focus.requestFocus() }

    val durationSecs = BanTimes.durationSeconds(
        days.toIntOrNull() ?: 0,
        hours.toIntOrNull() ?: 0,
        minutes.toIntOrNull() ?: 0,
    )
    val canSubmit = (banCertificate || banIp) && (permanent || durationSecs > 0)

    fun applyDuration(seconds: Int) {
        val parts = durationFieldValues(seconds)
        days = parts.first
        hours = parts.second
        minutes = parts.third
        endTooEarly = false
    }

    fun submit() {
        if (!canSubmit) return
        onConfirm(reason, banCertificate, banIp, if (permanent) 0 else durationSecs)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ban_user_title, userName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.moderation_reason)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .focusRequester(focus),
                )
                if (showBanOptions) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Checkbox(
                            checked = banCertificate,
                            onCheckedChange = { banCertificate = it },
                            enabled = initial.optionsEnabled,
                        )
                        Text(stringResource(R.string.ban_user_certificate))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = banIp,
                            onCheckedChange = { banIp = it },
                            enabled = initial.optionsEnabled,
                        )
                        Text(stringResource(R.string.ban_user_ip))
                    }
                }
                BanDurationFields(
                    startInstant = startInstant,
                    permanent = permanent,
                    onPermanentChange = { permanent = it },
                    days = days,
                    hours = hours,
                    minutes = minutes,
                    onDaysChange = {
                        days = it
                        endTooEarly = false
                    },
                    onHoursChange = {
                        hours = it
                        endTooEarly = false
                    },
                    onMinutesChange = {
                        minutes = it
                        endTooEarly = false
                    },
                    durationSecs = durationSecs,
                    endTooEarly = endTooEarly,
                    onApplyDuration = { applyDuration(it) },
                    onEndTooEarly = { endTooEarly = true },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = canSubmit) {
                Text(stringResource(R.string.ban_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
