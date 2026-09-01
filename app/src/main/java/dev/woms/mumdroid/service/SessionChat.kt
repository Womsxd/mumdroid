package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.net.MumbleClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** In-session chat log (channel, private, and system lines). */
internal class SessionChat(private val scope: CoroutineScope) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    fun clear() {
        _messages.value = emptyList()
    }

    fun appendSync(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun appendAsync(message: ChatMessage) {
        if (message.text.isEmpty()) return
        scope.launch {
            _messages.value = _messages.value + message
        }
    }

    fun appendSystem(serverName: String, message: String) {
        if (message.isEmpty()) return
        appendAsync(
            ChatMessage(
                actorName = serverName,
                text = message,
                isSystem = true,
            ),
        )
    }

    fun sendToChannel(
        client: MumbleClient?,
        channelId: Int,
        text: String,
        actorName: String,
        channelName: String,
    ) {
        if (text.isBlank()) return
        val c = client ?: return
        scope.launch {
            c.sendTextToChannel(channelId, text)
            appendSync(
                ChatMessage(
                    actorName = actorName,
                    channelId = channelId,
                    channelName = channelName,
                    text = text,
                    isOutgoing = true,
                ),
            )
        }
    }

    fun sendToUser(
        client: MumbleClient?,
        session: Int,
        text: String,
        actorName: String,
        targetName: String,
    ) {
        if (text.isBlank()) return
        val c = client ?: return
        scope.launch {
            c.sendTextToUser(session, text)
            appendSync(
                ChatMessage(
                    actorName = actorName,
                    text = text,
                    isOutgoing = true,
                    isPrivate = true,
                    targetSession = session,
                    targetName = targetName,
                ),
            )
        }
    }
}
