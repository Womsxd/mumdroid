package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.UserModeration
import dev.woms.mumdroid.core.net.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Server-side mute/deafen/priority-speaker for other users, plus the local
 * block/ignore lists (client-side only, no server action).
 */
internal class UserModerationCommands(
    private val scope: CoroutineScope,
    private val state: SessionState,
    private val roster: SessionRoster,
) {
    fun setLocalBlock(session: Int, blocked: Boolean) = roster.setLocalBlock(session, blocked)
    fun setLocalIgnore(session: Int, ignored: Boolean) = roster.setLocalIgnore(session, ignored)

    fun setRemoteMute(session: Int, muted: Boolean) {
        val c = state.client ?: return
        val user = roster.userMap[session]
        scope.launch {
            c.sendMessage(
                MessageType.USER_STATE,
                UserModeration.remoteMute(
                    session = session,
                    currentlyMuted = user?.mute ?: false,
                    currentlySuppressed = user?.suppress ?: false,
                    wantMuted = muted,
                ),
            )
        }
    }

    fun setRemoteDeafen(session: Int, deafened: Boolean) {
        val c = state.client ?: return
        scope.launch {
            c.sendMessage(
                MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(session)
                    .setDeaf(deafened).build(),
            )
        }
    }

    fun setPrioritySpeaker(session: Int, enabled: Boolean) {
        val c = state.client ?: return
        scope.launch {
            c.sendMessage(
                MessageType.USER_STATE,
                UserModeration.prioritySpeaker(session, enabled),
            )
        }
    }
}
