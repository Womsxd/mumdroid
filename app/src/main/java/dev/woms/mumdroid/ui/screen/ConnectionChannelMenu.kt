package dev.woms.mumdroid.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelLinks

/** Channel long-press menu: join, listen, shout, add/edit/remove, link, send. */
@Composable
internal fun ChannelContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showJoin: Boolean,
    showListen: Boolean,
    listening: Boolean,
    showShout: Boolean,
    shoutActive: Boolean,
    onShout: () -> Unit,
    onStopShout: () -> Unit,
    showAdd: Boolean,
    showEdit: Boolean,
    showRemove: Boolean,
    showSend: Boolean,
    linkMenu: ChannelLinks.Menu,
    onJoin: () -> Unit,
    onToggleListen: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onUnlinkAll: () -> Unit,
    onSend: () -> Unit,
) {
    val showAdmin = showAdd || showEdit || showRemove
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (showJoin) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.join_channel)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onJoin()
                },
            )
        }
        if (showListen) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (listening) R.string.stop_listening_channel
                            else R.string.listening_channel,
                        )
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Hearing, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onToggleListen()
                },
            )
        }
        if (showShout) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (shoutActive) R.string.stop_voice_target else R.string.shout_to_channel
                        )
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                onClick = {
                    onDismiss()
                    if (shoutActive) onStopShout() else onShout()
                },
            )
        }
        if ((showJoin || showListen || showShout) && (showAdmin || linkMenu.any || showSend)) {
            HorizontalDivider()
        }
        if (showAdd) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_channel)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAdd()
                },
            )
        }
        if (showEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_channel)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onEdit()
                },
            )
        }
        if (showRemove) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_channel)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onRemove()
                },
            )
        }
        if (showAdmin && (linkMenu.any || showSend)) {
            HorizontalDivider()
        }
        if (linkMenu.showLink) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.link_channel)) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onLink()
                },
            )
        }
        if (linkMenu.showUnlink) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.unlink_channel)) },
                leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onUnlink()
                },
            )
        }
        if (linkMenu.showUnlinkAll) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.unlink_all_channels)) },
                leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onUnlinkAll()
                },
            )
        }
        if (linkMenu.any && showSend) {
            HorizontalDivider()
        }
        if (showSend) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.send_message)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    onSend()
                },
            )
        }
    }
}
