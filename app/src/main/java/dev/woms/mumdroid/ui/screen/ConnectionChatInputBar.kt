package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.User

@Composable
internal fun ChatInputBar(
    users: List<User>,
    channels: List<Channel>,
    channelId: Int,
    onSend: (Int, String) -> Unit,
    onSendPrivate: (Int, String) -> Unit,
) {
    // Text/target state (prefix selection, picker, destination). Kept in a
    // plain object so the prefix rules are unit-testable.
    val composer = remember(channelId) { ChatComposer(defaultChannelId = channelId) }
    // Re-render trigger: the composer is mutable but not observable.
    var revision by remember { mutableStateOf(0) }
    // A plain (non-observable) revision is enough for *reading* the composer,
    // but the value handed to BasicTextField must never be memoised on it: the
    // memo key survives the state reset, which would resurrect a stale
    // TextFieldValue and reset the caret.
    revision
    val text = TextFieldValue(composer.text, selection = composer.selection)
    val prefix = composer.prefix
    val pickerUser = composer.picker == ChatComposer.Picker.USER
    val pickerChannel = composer.picker == ChatComposer.Picker.CHANNEL

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
            if (composer.hasHighlightedPrefix) {
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

    fun send() {
        val (destination, message) = composer.consume() ?: return
        when (destination) {
            is ChatComposer.Destination.Private -> onSendPrivate(destination.session, message)
            is ChatComposer.Destination.Channel -> onSend(destination.channelId, message)
        }
        revision++
    }

    Column {
        // Inline picker list shown above the input row (never opens a dialog so
        // typing is never interrupted). Only one list is shown at a time.
        when {
            pickerUser -> ChatUserPicker(
                users = users,
                onSelect = { user ->
                    composer.selectUser(user)
                    revision++
                },
            )
            pickerChannel -> ChatChannelPicker(
                channels = channels,
                onSelect = { channelId ->
                    ChannelTree.find(channels, channelId)?.let { composer.selectChannel(it) }
                    revision++
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
                            // Keep the caret/selection the platform editor
                            // reports, otherwise it is reset to the start.
                            composer.onTextChanged(new.text, new.selection)
                            revision++
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
    onSelect: (Int) -> Unit,
) {
    val flat = remember(channels) { ChannelTree.flattenForPicker(channels) }
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
                items(flat, key = { it.id }) { pick ->
                    TextButton(
                        onClick = { onSelect(pick.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            pick.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
