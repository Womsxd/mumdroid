package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel

/**
 * Desktop `ACLEditor` channel-properties tab: name, password, plain-text
 * description, position, max users, and (when adding) temporary.
 */
@Composable
fun ChannelEditDialog(
    channel: Channel?,
    parentName: String,
    forceTemporary: Boolean,
    incomingDescription: String,
    incomingPassword: String,
    onConfirm: (
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        temporary: Boolean,
        password: String,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val isAdd = channel == null
    var name by remember {
        mutableStateOf(if (isAdd) "" else channel.name)
    }
    var password by remember {
        mutableStateOf(if (isAdd) "" else incomingPassword)
    }
    var description by remember {
        mutableStateOf(if (isAdd) "" else channel.description)
    }
    var positionText by remember {
        mutableStateOf(if (isAdd) "0" else channel.position.toString())
    }
    var maxUsersText by remember {
        mutableStateOf(if (isAdd) "0" else channel.maxUsers.toString())
    }
    var temporary by remember { mutableStateOf(forceTemporary) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(isAdd, channel?.id) { focus.requestFocus() }
    LaunchedEffect(incomingDescription) {
        if (description.isEmpty() && incomingDescription.isNotEmpty()) {
            description = incomingDescription
        }
    }
    LaunchedEffect(incomingPassword) {
        if (password.isEmpty() && incomingPassword.isNotEmpty()) {
            password = incomingPassword
        }
    }
    LaunchedEffect(forceTemporary) {
        if (forceTemporary) temporary = true
    }

    val nameLocked = !isAdd && channel.id == 0
    val canSubmit = name.trim().isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        onConfirm(
            name.trim(),
            description,
            parseSignedInt(positionText, channel?.position ?: 0),
            parseUnsignedInt(maxUsersText, channel?.maxUsers ?: 0),
            if (isAdd) temporary else false,
            password,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isAdd) {
                    stringResource(R.string.add_channel_title)
                } else {
                    stringResource(R.string.edit_channel_title, channel.name)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (isAdd) {
                    Text(
                        stringResource(R.string.add_channel_under, parentName),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_name)) },
                    enabled = !nameLocked,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.channel_description)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = positionText,
                    onValueChange = { positionText = filterSignedInt(it) },
                    label = { Text(stringResource(R.string.channel_position)) },
                    supportingText = { Text(stringResource(R.string.channel_position_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = maxUsersText,
                    onValueChange = { maxUsersText = filterUnsignedInt(it) },
                    label = { Text(stringResource(R.string.channel_max_users)) },
                    supportingText = { Text(stringResource(R.string.channel_max_users_default)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.channel_password_label)) },
                    supportingText = { Text(stringResource(R.string.channel_password_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (isAdd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.channel_temporary),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = temporary,
                            onCheckedChange = { if (!forceTemporary) temporary = it },
                            enabled = !forceTemporary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = canSubmit) {
                Text(stringResource(if (isAdd) R.string.add_channel else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun RemoveChannelDialog(
    channelName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var secondStep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_channel)) },
        text = {
            Text(
                stringResource(
                    if (secondStep) {
                        R.string.remove_channel_confirm_again
                    } else {
                        R.string.remove_channel_confirm
                    },
                    channelName,
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (secondStep) onConfirm() else secondStep = true
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun filterSignedInt(raw: String): String {
    val filtered = raw.filterIndexed { index, c ->
        c.isDigit() || (c == '-' && index == 0)
    }
    return if (filtered.length > 1 && filtered.startsWith("-")) {
        "-" + filtered.drop(1).take(10)
    } else {
        filtered.take(11)
    }
}

private fun filterUnsignedInt(raw: String): String =
    raw.filter { it.isDigit() }.take(10)

private fun parseSignedInt(text: String, fallback: Int): Int {
    if (text.isBlank() || text == "-") return fallback
    return text.toIntOrNull() ?: fallback
}

private fun parseUnsignedInt(text: String, fallback: Int): Int {
    if (text.isBlank()) return fallback
    return text.toIntOrNull()?.coerceAtLeast(0) ?: fallback
}
