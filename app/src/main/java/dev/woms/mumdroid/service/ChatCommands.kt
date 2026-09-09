package dev.woms.mumdroid.service

/** Outgoing channel and private chat. */
internal class ChatCommands(
    private val state: SessionState,
    private val roster: SessionRoster,
    private val chat: SessionChat,
) {
    fun sendChat(channelId: Int, text: String) {
        chat.sendToChannel(state.client, channelId, text, state.serverName.value, roster.channelName(channelId))
    }

    fun sendPrivateChat(session: Int, text: String) {
        val targetName = roster.userMap[session]?.name ?: session.toString()
        chat.sendToUser(state.client, session, text, state.serverName.value, targetName)
    }
}
