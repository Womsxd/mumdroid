package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User

/**
 * Confirmation dialog for a channel shout: links / children switches.
 *
 * The two switches are the protocol's own receiver-set flags, so they are
 * presented explicitly rather than hidden behind a mode.
 *
 * The ACL group restriction is temporarily hidden: a free-text field invites
 * typos that silently match nobody, and the proper picker needs ACL data this
 * client does not keep yet. The [group] parameter is still threaded through
 * (always empty from here) so the dialog callers, target spec and wire encoding
 * stay unchanged; re-enabling is just restoring the text field below.
 */
@Composable
internal fun ShoutToChannelDialog(
    channelName: String,
    onConfirm: (links: Boolean, children: Boolean, group: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var links by remember { mutableStateOf(false) }
    var children by remember { mutableStateOf(false) }
    // ACL group input hidden for now — see the dialog KDoc. Kept as a value so
    // the confirm callback signature does not change when it comes back.
    val group = ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shout_dialog_title, channelName)) },
        text = {
            Column {
                SwitchRow(
                    label = stringResource(R.string.shout_include_links),
                    checked = links,
                    onCheckedChange = { links = it },
                )
                SwitchRow(
                    label = stringResource(R.string.shout_include_children),
                    checked = children,
                    onCheckedChange = { children = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(links, children, group.trim()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Multi-select user list for a whisper target. Sessions are the protocol's own
 * addressing scheme for whisper receivers, so the selection is sent as-is.
 */
@Composable
internal fun WhisperToUsersDialog(
    users: List<User>,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local user and listener proxies are not meaningful whisper receivers;
    // the channel tree already flattens the roster, so it is de-duplicated here.
    val candidates = remember(users) {
        users.filter { !it.isLocalUser && !it.isChannelListener && it.session != 0 }
            .distinctBy { it.session }
    }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_target_label)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.voice_target_unavailable))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(candidates, key = { it.session }) { user ->
                        val checked = user.session in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) {
                                        selected - user.session
                                    } else {
                                        selected + user.session
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = user.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Channel picker used by the "shout to channel..." entry of the voice bar menu. */
@Composable
internal fun ShoutChannelPickerDialog(
    channels: List<ChannelPick>,
    onSelect: (ChannelPick) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shout_to_channel)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(channels, key = { it.id }) { channel ->
                    Text(
                        text = channel.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(channel) }
                            .padding(
                                start = (channel.indent * 16 + 8).dp,
                                top = 10.dp,
                                end = 8.dp,
                                bottom = 10.dp,
                            ),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
