package dev.woms.mumdroid.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanAddresses
import dev.woms.mumdroid.core.model.BanTimes
import dev.woms.mumdroid.core.net.BanEntry
import java.time.Instant
import kotlin.math.roundToInt

internal data class BanEditorState(
    val index: Int? = null,
    val name: String = "",
    val address: String = "",
    val mask: Int = BanAddresses.IPV4_MASK_RANGE.last,
    val hash: String = "",
    val reason: String = "",
    val permanent: Boolean = true,
    val days: Int = 1,
    val hours: Int = 0,
    val minutes: Int = 0,
    val start: String = "",
) {
    companion object {
        fun from(ban: BanEntry, index: Int): BanEditorState {
            val duration = ban.duration.coerceAtLeast(0)
            return BanEditorState(
                index = index,
                name = ban.name,
                address = BanAddresses.displayAddress(ban.address),
                mask = BanAddresses.displayMask(ban.address, ban.mask),
                hash = ban.hash,
                reason = ban.reason,
                permanent = duration == 0,
                days = duration / 86_400,
                hours = (duration % 86_400) / 3_600,
                minutes = (duration % 3_600) / 60,
                start = ban.start,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BanEditScreen(
    state: BanEditorState,
    onSave: (BanEntry) -> Unit,
    onUnban: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var name by remember { mutableStateOf(state.name) }
    var address by remember { mutableStateOf(state.address) }
    var mask by remember { mutableIntStateOf(state.mask) }
    var lastKind by remember { mutableStateOf(BanAddresses.parseKind(state.address)) }
    var hash by remember { mutableStateOf(state.hash) }
    var reason by remember { mutableStateOf(state.reason) }
    var permanent by remember { mutableStateOf(state.permanent) }
    var days by remember { mutableStateOf(state.days.toString()) }
    var hours by remember { mutableStateOf(state.hours.toString()) }
    var minutes by remember { mutableStateOf(state.minutes.toString()) }
    var endTooEarly by remember { mutableStateOf(false) }
    var showExpiredUnban by remember { mutableStateOf(false) }

    val kind = BanAddresses.parseKind(address)
    val durationSecs = BanTimes.durationSeconds(
        days.toIntOrNull() ?: 0,
        hours.toIntOrNull() ?: 0,
        minutes.toIntOrNull() ?: 0,
    )
    val originalHadIp = BanAddresses.encode(state.address, state.mask)?.first?.isNotEmpty() == true
    val canSave = BanAddresses.canSave(
        address = address,
        mask = mask,
        hash = hash,
        permanent = permanent,
        durationSeconds = durationSecs,
        requireIp = state.index == null || originalHadIp,
    )

    val startInstant = BanTimes.parse(state.start) ?: Instant.now()

    fun applyDuration(seconds: Int) {
        val parts = durationFieldValues(seconds)
        days = parts.first
        hours = parts.second
        minutes = parts.third
        endTooEarly = false
    }

    fun persist() {
        val pair = BanAddresses.encode(address, mask) ?: return
        val startIso = state.start.ifBlank { BanTimes.nowIso() }
        if (!permanent) {
            val savedStart = BanTimes.parse(startIso) ?: Instant.now()
            if (BanTimes.isEffectivelyExpired(savedStart, durationSecs)) {
                showExpiredUnban = true
                return
            }
        }
        onSave(
            BanEntry(
                address = pair.first,
                mask = pair.second,
                name = name.trim(),
                hash = hash.trim(),
                reason = reason.trim(),
                start = startIso,
                duration = if (permanent) 0 else durationSecs,
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.index == null) R.string.add_ban else R.string.edit_ban,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { persist() },
                        enabled = canSave,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BanSection(stringResource(R.string.ban_section_target)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ban_user_name)) },
                    placeholder = { Text(stringResource(R.string.ban_field_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { next ->
                        address = next
                        val nextKind = BanAddresses.parseKind(next)
                        if (nextKind != null) {
                            val range = BanAddresses.maskRange(nextKind)
                            val previous = lastKind
                            mask = if (previous != null && previous != nextKind &&
                                mask == BanAddresses.maskRange(previous).last
                            ) {
                                range.last
                            } else {
                                mask.coerceIn(range)
                            }
                            lastKind = nextKind
                        }
                    },
                    label = { Text(stringResource(R.string.ban_address)) },
                    supportingText = if (address.isNotBlank() && kind == null) {
                        { Text(stringResource(R.string.ban_address_invalid)) }
                    } else {
                        null
                    },
                    isError = address.isNotBlank() && kind == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind != null) {
                    val range = BanAddresses.maskRange(kind)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.ban_mask, mask),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Slider(
                            value = mask.toFloat(),
                            onValueChange = { mask = it.roundToInt().coerceIn(range) },
                            valueRange = range.first.toFloat()..range.last.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = hash,
                    onValueChange = { hash = it },
                    label = { Text(stringResource(R.string.ban_hash)) },
                    placeholder = { Text(stringResource(R.string.ban_field_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.ban_reason)) },
                    placeholder = { Text(stringResource(R.string.ban_field_optional)) },
                    minLines = 1,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            BanSection(stringResource(R.string.ban_section_duration)) {
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
                )
            }
        }
    }

    if (showExpiredUnban) {
        ExpiredBanUnbanDialog(
            onConfirm = {
                showExpiredUnban = false
                onUnban()
            },
            onDismiss = { showExpiredUnban = false },
        )
    }
}

@Composable
private fun BanSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ExpiredBanUnbanDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ban_expired_unban_title)) },
        text = { Text(stringResource(R.string.ban_expired_unban_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.ban_unban))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
