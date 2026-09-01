package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.model.User

/** Chat panel with inline @ (private message) and # (channel) pickers. */
@Composable
internal fun ChatPanel(
    messages: List<ChatMessage>,
    users: List<User>,
    channels: List<Channel>,
    channelId: Int,
    onSend: (Int, String) -> Unit,
    onSendPrivate: (Int, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ChatMessageList(
            messages = messages,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        ChatInputBar(
            users = users,
            channels = channels,
            channelId = channelId,
            onSend = onSend,
            onSendPrivate = onSendPrivate,
        )
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(messages) { msg ->
            ChatMessageItem(msg)
        }
    }
}

@Composable
private fun ChatMessageItem(msg: ChatMessage) {
    val colorScheme = MaterialTheme.colorScheme
    if (msg.isSystem) {
        // System/server messages (incl. join/leave/move hints) are
        // shown without a sender prefix.
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    } else {
        // Distinguish sent vs received messages and make the target
        // context explicit: the channel a message is sent to / received
        // from, or the user a private message is addressed to.
        val you = stringResource(R.string.you)
        val header = when {
            msg.isPrivate && msg.isOutgoing ->
                stringResource(
                    R.string.chat_private_header,
                    you,
                    msg.targetName.ifEmpty { msg.targetSession.toString() },
                )
            msg.isPrivate ->
                stringResource(R.string.chat_private_header, msg.actorName, you)
            else ->
                stringResource(
                    R.string.chat_channel_header,
                    if (msg.isOutgoing) you else msg.actorName,
                    msg.channelName,
                )
        }
        val isPrivate = msg.isPrivate
        Surface(
            color = if (msg.isOutgoing) colorScheme.primaryContainer
            else colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (isPrivate) colorScheme.primary
                            else colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    ) {
                        append(header)
                    }
                    append(": ")
                    append(msg.text)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                fontStyle = if (isPrivate) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    users: List<User>,
    channels: List<Channel>,
    channelId: Int,
    onSend: (Int, String) -> Unit,
    onSendPrivate: (Int, String) -> Unit,
) {
    // Full editable text value. The selected @name/#channel prefix is stored
    // literally in this text and only styled/highlighted when rendered.
    var text by remember { mutableStateOf(TextFieldValue("")) }
    // Inline picker visibility flags. Only one of the two lists is open at a
    // time: pickerUser shows the @ user list, pickerChannel the # channel list.
    var pickerUser by remember { mutableStateOf(false) }
    var pickerChannel by remember { mutableStateOf(false) }
    // Targets (mutually exclusive): @ picks a user for a private message, # picks a channel.
    var privateTarget by remember { mutableStateOf<User?>(null) }
    var channelTarget by remember { mutableStateOf<Channel?>(null) }

    // The highlighted prefix rendered for the selected target (or empty).
    val prefix = privateTarget?.let { "@${it.name} " }
        ?: channelTarget?.let { "#${it.name} " }
        ?: ""

    // Strip the current selection prefix so we work with the real message body.
    fun cleanInput(t: String): String =
        if (prefix.isNotEmpty() && t.startsWith(prefix)) t.removePrefix(prefix) else t

    // MaterialTheme.colorScheme is a @Composable read, so it must be read in
    // the composable function body, not inside the non-composable
    // remember/buildAnnotatedString blocks.
    val colorScheme = MaterialTheme.colorScheme

    // The value passed to the field: prefix is given a background colour and a
    // contrasting text colour; the cursor follows the real text selection.
    val displayed = remember(text, prefix) {
        val t = text.text
        val prefixBg = colorScheme.primaryContainer
        val prefixFg = colorScheme.onPrimaryContainer
        val annotated = buildAnnotatedString {
            if (prefix.isNotEmpty() && t.startsWith(prefix)) {
                withStyle(
                    SpanStyle(
                        background = prefixBg,
                        color = prefixFg,
                        fontWeight = FontWeight.SemiBold,
                    )
                ) {
                    append(prefix)
                }
                append(t.removePrefix(prefix))
            } else {
                append(t)
            }
        }
        TextFieldValue(annotated, selection = text.selection)
    }

    fun clearTargets() {
        privateTarget = null
        channelTarget = null
    }

    // Apply a chosen target, placing the cursor at the end of the text. Any
    // previously selected @xxx/#xxx prefix is stripped first so switching the
    // target replaces the old one (only one target at a time).
    fun selectTarget(newPrefix: String) {
        var t = text.text
        // The @/# that opened the picker is kept in the text (it is only
        // removed once a target is actually chosen). Drop that trailing char
        // before inserting the new prefix.
        if (t.endsWith("@") || t.endsWith("#")) {
            t = t.dropLast(1)
        }
        val rest = when {
            t.startsWith("@") || t.startsWith("#") -> {
                val end = t.indexOf(' ', 1)
                if (end >= 0) t.substring(end + 1) else ""
            }
            else -> t
        }
        val newText = newPrefix + rest
        text = TextFieldValue(newText, selection = TextRange(newText.length))
    }

    fun send() {
        val body = cleanInput(text.text).trim()
        if (body.isBlank()) return
        val target = privateTarget
        val chan = channelTarget
        when {
            target != null -> onSendPrivate(target.session, body)
            chan != null -> onSend(chan.id, body)
            else -> onSend(channelId, body)
        }
        text = TextFieldValue("")
        clearTargets()
    }

    Column {
        // Inline picker list shown above the input row (never opens a dialog so
        // typing is never interrupted). Only one list is shown at a time.
        when {
            pickerUser -> ChatUserPicker(
                users = users,
                onSelect = { user ->
                    privateTarget = user
                    channelTarget = null
                    pickerUser = false
                    pickerChannel = false
                    selectTarget("@${user.name} ")
                },
            )
            pickerChannel -> ChatChannelPicker(
                channels = channels,
                onSelect = { channel ->
                    channelTarget = channel
                    privateTarget = null
                    pickerChannel = false
                    pickerUser = false
                    selectTarget("#${channel.name} ")
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The field no longer has dedicated @/# buttons: typing '@' or '#'
            // in the box opens the corresponding inline picker. The selected
            // @name/#channel text is kept as a real (highlighted) prefix so the
            // user can cancel the selection by deleting it.
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.text.isEmpty()) {
                        Text(
                            stringResource(R.string.message_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = displayed,
                        onValueChange = { new ->
                            val newText = new.text
                            // Typing @ or # opens the matching inline picker but
                            // keeps the @/# in the text. The char is only removed
                            // (and replaced with the selected @name/#channel
                            // prefix) once the user actually picks a target.
                            val last = newText.lastOrNull()
                            if (last == '@' || last == '#') {
                                if (last == '@') {
                                    pickerUser = true
                                    pickerChannel = false
                                } else {
                                    pickerChannel = true
                                    pickerUser = false
                                }
                            } else if (pickerUser || pickerChannel) {
                                // The user continued typing without picking a
                                // target: close the open picker.
                                pickerUser = false
                                pickerChannel = false
                            }
                            // Deleting the highlighted selection cancels it and
                            // does not re-add it.
                            if (prefix.isNotEmpty() && !newText.startsWith(prefix)) {
                                clearTargets()
                            }
                            text = TextFieldValue(newText, selection = TextRange(newText.length))
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            IconButton(
                onClick = { send() },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

@Composable
private fun ChatUserPicker(
    users: List<User>,
    onSelect: (User) -> Unit,
) {
    val targets = users.filter { !it.isLocalUser }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.select_private_recipient),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (targets.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_users_to_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(targets, key = { it.session }) { user ->
                    TextButton(
                        onClick = { onSelect(user) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            user.name,
                            fontWeight = if (user.isLocalUser) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatChannelPicker(
    channels: List<Channel>,
    onSelect: (Channel) -> Unit,
) {
    val flat = remember(channels) { flattenChannels(channels) }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.select_channel_target),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                items(flat, key = { it.id }) { channel ->
                    TextButton(
                        onClick = { onSelect(channel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Flattens a channel tree into a depth-first list for the # picker. */
private fun flattenChannels(channels: List<Channel>): List<Channel> {
    val out = mutableListOf<Channel>()
    fun visit(c: Channel) {
        out.add(c)
        c.children.forEach { visit(it) }
    }
    channels.forEach { visit(it) }
    return out
}
