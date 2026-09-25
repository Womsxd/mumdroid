package dev.woms.mumdroid.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChanACL

// The pieces both ACL tabs build from: the permission catalogue the editor and
// its dialogs agree on, and the two widgets (a name field, a titled section)
// that the ACL tab and ChannelAclGroupsTab share.

/**
 * One permission bit with its label and explanation, in the desktop
 * `ACLEditor` row order (`ChanACL::permName` / `whatsThis`).
 */
internal data class AclPermission(
    val bit: Int,
    @StringRes val name: Int,
    @StringRes val description: Int,
)

internal val ACL_PERMISSIONS = listOf(
    AclPermission(ChanACL.WRITE, R.string.acl_perm_write, R.string.acl_perm_write_desc),
    AclPermission(ChanACL.TRAVERSE, R.string.acl_perm_traverse, R.string.acl_perm_traverse_desc),
    AclPermission(ChanACL.ENTER, R.string.acl_perm_enter, R.string.acl_perm_enter_desc),
    AclPermission(ChanACL.SPEAK, R.string.acl_perm_speak, R.string.acl_perm_speak_desc),
    AclPermission(ChanACL.MUTE_DEAFEN, R.string.acl_perm_mute_deafen, R.string.acl_perm_mute_deafen_desc),
    AclPermission(ChanACL.MOVE, R.string.acl_perm_move, R.string.acl_perm_move_desc),
    AclPermission(ChanACL.MAKE_CHANNEL, R.string.acl_perm_make_channel, R.string.acl_perm_make_channel_desc),
    AclPermission(ChanACL.LINK_CHANNEL, R.string.acl_perm_link_channel, R.string.acl_perm_link_channel_desc),
    AclPermission(ChanACL.WHISPER, R.string.acl_perm_whisper, R.string.acl_perm_whisper_desc),
    AclPermission(ChanACL.TEXT_MESSAGE, R.string.acl_perm_text_message, R.string.acl_perm_text_message_desc),
    AclPermission(
        ChanACL.MAKE_TEMP_CHANNEL,
        R.string.acl_perm_make_temp_channel,
        R.string.acl_perm_make_temp_channel_desc,
    ),
    AclPermission(ChanACL.LISTEN, R.string.acl_perm_listen, R.string.acl_perm_listen_desc),
    AclPermission(ChanACL.KICK, R.string.acl_perm_kick, R.string.acl_perm_kick_desc),
    AclPermission(ChanACL.BAN, R.string.acl_perm_ban, R.string.acl_perm_ban_desc),
    AclPermission(
        ChanACL.RESET_USER_CONTENT,
        R.string.acl_perm_reset_user_content,
        R.string.acl_perm_reset_user_content_desc,
    ),
    AclPermission(ChanACL.REGISTER, R.string.acl_perm_register, R.string.acl_perm_register_desc),
    AclPermission(ChanACL.SELF_REGISTER, R.string.acl_perm_self_register, R.string.acl_perm_self_register_desc),
)

/** A labelled text field with a dropdown of known names, committed on Done. */
@Composable
internal fun AclNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    enabled: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            supportingText = supporting?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            trailingIcon = {
                IconButton(onClick = { open = true }, enabled = enabled && suggestions.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.acl_query_user),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        open = false
                        onValueChange(suggestion)
                        onCommit()
                    },
                )
            }
        }
    }
}

/**
 * One titled block of the editor: the same shape `BanEditScreen` gives its
 * sections, so the two administration screens read alike. [action] sits on the
 * title line where a section needs one (the group delete).
 */
@Composable
internal fun AclSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        content()
    }
}
