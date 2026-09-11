package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User

/**
 * The user row's modals — move, kick, ban, register, send message — and the
 * state that says which one is open. Held together because they are only ever
 * reached from the same long-press menu, through the one [UserRowDialogHost].
 *
 * Which of the user's modal dialogs is open is kept in [UserRowDialogs] so the
 * five booleans are not five separate `remember { mutableStateOf(false) }` in
 * the composable body.
 */
internal class UserRowDialogs {
    var move by mutableStateOf(false)
    var kick by mutableStateOf(false)
    var ban by mutableStateOf(false)
    var register by mutableStateOf(false)
    var send by mutableStateOf(false)
}

/** The modal host of a user row: move / kick / ban / register / send message. */
@Composable
internal fun UserRowDialogHost(
    user: User,
    dialogs: UserRowDialogs,
    moveDests: List<ChannelPick>,
    supportsSelectiveBan: () -> Boolean,
    onMoveUser: (Int, Int) -> Unit,
    onKickUser: (Int, String) -> Unit,
    onBanUser: (Int, String, Boolean, Boolean, Int) -> Unit,
    onRegisterUser: (Int) -> Unit,
    onSendPrivateChat: (Int, String) -> Unit,
) {
    if (dialogs.move) {
        MoveUserChannelDialog(
            userName = user.name,
            channels = moveDests,
            currentChannelId = user.channelId,
            onSelect = { channelId ->
                dialogs.move = false
                onMoveUser(user.session, channelId)
            },
            onDismiss = { dialogs.move = false },
        )
    }
    if (dialogs.kick) {
        KickUserDialog(
            userName = user.name,
            onConfirm = { reason ->
                dialogs.kick = false
                onKickUser(user.session, reason)
            },
            onDismiss = { dialogs.kick = false },
        )
    }
    if (dialogs.ban) {
        BanUserDialog(
            userName = user.name,
            hasCertificate = user.hash.isNotEmpty(),
            showBanOptions = supportsSelectiveBan(),
            onConfirm = { reason, banCertificate, banIp, duration ->
                dialogs.ban = false
                onBanUser(user.session, reason, banCertificate, banIp, duration)
            },
            onDismiss = { dialogs.ban = false },
        )
    }
    if (dialogs.register) {
        RegisterUserDialog(
            userName = user.name,
            isSelf = user.isLocalUser,
            onConfirm = {
                dialogs.register = false
                onRegisterUser(user.session)
            },
            onDismiss = { dialogs.register = false },
        )
    }
    if (dialogs.send) {
        SendTextMessageDialog(
            title = stringResource(R.string.send_user_message_title, user.name),
            onConfirm = { text ->
                dialogs.send = false
                onSendPrivateChat(user.session, text)
            },
            onDismiss = { dialogs.send = false },
        )
    }
}

/**
 * Desktop `QMessageBox` before `ServerHandler::registerUser`. Self-register
 * and admin-register use different titles and warnings.
 */
@Composable
fun RegisterUserDialog(
    userName: String,
    isSelf: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isSelf) R.string.register_self_title else R.string.register_other_title,
                    userName,
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (isSelf) R.string.register_self_message else R.string.register_other_message,
                    userName,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.register_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Desktop `QInputDialog` for kicking a user: reason, then send `UserRemove`. */
@Composable
fun KickUserDialog(
    userName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(userName) { focus.requestFocus() }

    fun submit() {
        onConfirm(reason)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kick_user_title, userName)) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            Button(onClick = { submit() }) {
                Text(stringResource(R.string.kick_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

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
