package dev.woms.mumdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User

/**
 * Which of the user's modal dialogs is open. Held by [UserRow] so the five
 * booleans are not five separate `remember { mutableStateOf(false) }` in the
 * composable body.
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
