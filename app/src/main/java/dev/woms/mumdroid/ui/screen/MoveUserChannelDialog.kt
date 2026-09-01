package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick

/**
 * Picks a destination channel for moving another user, standing in for
 * desktop tree drag-and-drop (`UserModel::dropMimeData`).
 */
@Composable
fun MoveUserChannelDialog(
    userName: String,
    channels: List<ChannelPick>,
    currentChannelId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = channels.filter { it.id != currentChannelId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_user_title, userName)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.move_user_no_channels))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(targets, key = { it.id }) { channel ->
                        Text(
                            text = channel.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(channel.id) }
                                .padding(
                                    start = (channel.indent * 16 + 8).dp,
                                    top = 10.dp,
                                    end = 8.dp,
                                    bottom = 10.dp,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
