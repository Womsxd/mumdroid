package dev.woms.mumdroid.ui.screen

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.model.ChatTime
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
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        itemsIndexed(messages) { index, msg ->
            val previous = messages.getOrNull(index - 1)
            if (previous == null || !ChatTime.sameLocalDate(previous.timestamp, msg.timestamp)) {
                ChatDateChangedItem(msg.timestamp)
            }
            ChatMessageItem(msg, use24Hour)
        }
    }
}

@Composable
private fun ChatDateChangedItem(timestamp: Long) {
    Text(
        text = stringResource(R.string.chat_date_changed, ChatTime.formatDate(timestamp)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ChatMessageItem(msg: ChatMessage, use24Hour: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val timeLabel = remember(msg.timestamp, use24Hour) {
        ChatTime.formatTime(msg.timestamp, use24Hour)
    }
    if (msg.isSystem) {
        // System/server messages (incl. join/leave/move hints) are
        // shown without a sender prefix.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colorScheme.onSurfaceVariant)) {
                    append(timeLabel)
                    append(" ")
                }
                append(msg.text)
            },
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
                    withStyle(SpanStyle(color = colorScheme.onSurfaceVariant)) {
                        append(timeLabel)
                        append(" ")
                    }
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
