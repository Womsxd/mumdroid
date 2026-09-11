package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.core.net.MessageType
import dev.woms.mumdroid.core.net.UserModeration
import dev.woms.mumdroid.data.ChannelAccessTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The session's UI-facing surface, grouped by the collaborator each part drives.
 *
 * [MumbleService] exposes these as the surface its bound UI calls, and every one
 * of them is a thin adapter: it looks up the current client / roster entry and
 * forwards to [SessionChannels], [ServerAdminSession], [SessionRoster],
 * [VoiceSession] or [SessionChat]. They share one file because they share one
 * shape and one lifecycle — all of them are built together in `onCreate` and
 * read only through the service. Splitting them across files added navigation
 * cost without adding a boundary: no group can be understood or changed without
 * the same context objects ([SessionState], [SessionRoster] and the collaborator
 * it drives). [SessionPermissions] is the read-only half of the same surface
 * (the `can…` queries the UI uses to enable actions).
 */

/**
 * Registered-user and ban-list management, kick/ban/register, user stats,
 * comments/textures, channel ACL, access tokens and the channel-password
 * prompt.
 */
internal class AdminCommands(
    private val state: SessionState,
    private val roster: SessionRoster,
    private val admin: ServerAdminSession,
    private val channelAccessTokenStore: ChannelAccessTokenStore,
) {
    fun requestUserStats(session: Int, statsOnly: Boolean = false) =
        admin.requestUserStats(state.client, session, statsOnly)

    fun clearUserStats() = admin.clearUserStats()

    fun kickUser(session: Int, reason: String) = admin.kickUser(state.client, session, reason)

    fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) = admin.banUser(
        state.client,
        session,
        roster.userMap[session],
        reason,
        banCertificate,
        banIp,
        duration,
    )

    fun registerUser(session: Int) = admin.registerUser(state.client, session)

    fun requestUserList(clear: Boolean = true) = admin.requestUserList(state.client, clear)
    fun renameRegisteredUser(userId: Int, newName: String) =
        admin.renameRegisteredUser(state.client, userId, newName)
    fun unregisterUser(userId: Int) = admin.unregisterUser(state.client, userId)
    fun requestBanList(clear: Boolean = true) = admin.requestBanList(state.client, clear)
    fun replaceBanList(bans: List<BanEntry>) = admin.replaceBanList(state.client, bans)

    fun requestChannelAcl(channelId: Int) = admin.requestAcl(state.client, channelId)
    fun sendChannelAcl(snapshot: ChanAclSnapshot) = admin.sendAcl(state.client, snapshot)
    fun queryAclUsersByName(names: List<String>) = admin.queryUsersByName(state.client, names)
    fun queryAclUsersById(ids: List<Int>) = admin.queryUsersById(state.client, ids)

    fun setUserComment(session: Int, comment: String) = admin.setUserComment(state.client, session, comment)
    fun resetUserComment(session: Int) = admin.resetUserComment(state.client, session)
    fun setUserTexture(session: Int, texture: ByteArray) = admin.setUserTexture(state.client, session, texture)
    fun resetUserTexture(session: Int) = admin.resetUserTexture(state.client, session)

    fun replaceAccessTokens(tokens: List<String>) =
        admin.replaceAccessTokens(tokens, channelAccessTokenStore, state.host, state.port, state.client)

    fun clearChannelPasswordPrompt() = admin.clearPasswordPrompt()
}

/**
 * Channel join / move / link / create-update-remove, listen-in and the lazy
 * permission query for a channel.
 */
internal class ChannelCommands(
    private val sessionChannels: SessionChannels,
    private val applyPassword: (channelId: Int, password: String) -> Unit,
) {
    fun joinChannel(channelId: Int, accessToken: String? = null) =
        sessionChannels.join(channelId, accessToken = accessToken)

    fun moveUser(session: Int, channelId: Int) = sessionChannels.moveUser(session, channelId)

    fun linkChannel(targetId: Int) = sessionChannels.link(targetId)
    fun unlinkChannel(targetId: Int) = sessionChannels.unlink(targetId)
    fun unlinkAllChannels() = sessionChannels.unlinkAll()

    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String = "",
    ) = sessionChannels.create(parentId, name, description, position, temporary, maxUsers, password)

    fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String = "",
    ) = sessionChannels.update(channelId, name, description, position, maxUsers, password, applyPassword)

    fun removeChannel(channelId: Int) = sessionChannels.remove(channelId)
    fun requestChannelDescription(channelId: Int) = sessionChannels.requestDescription(channelId)
    fun setChannelListening(channelId: Int, listen: Boolean) = sessionChannels.setListening(channelId, listen)
    fun ensureChannelPermissions(channelId: Int) = sessionChannels.ensurePermissions(channelId)
}

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

/**
 * Local voice controls: output route, self mute/deafen and push-to-talk. The
 * mute/deafen toggles also echo the new state to the server and roster.
 */
internal class VoiceCommands(
    private val scope: CoroutineScope,
    private val state: SessionState,
    private val roster: SessionRoster,
    private val voice: VoiceSession,
) {
    fun setOutputTarget(target: VoiceOutputTarget) = voice.setOutputTarget(target)

    fun toggleSelfMute() {
        voice.toggleSelfMute()
        sendLocalMuteDeafen()
    }

    fun toggleSelfDeafen() {
        voice.toggleSelfDeafen()
        sendLocalMuteDeafen()
    }

    fun startTalking() = voice.startTalking()
    fun stopTalking() = voice.stopTalking()

    /**
     * Sets the shout/whisper target. Null restores regular speech and revokes
     * every registration on the server.
     */
    fun setVoiceTarget(spec: VoiceTargetSpec?) {
        if (spec == null) voice.setVoiceTarget(null) else voice.setVoiceTarget(spec)
    }

    /** Clears the target, keeping the revocation messages on the wire. */
    fun clearVoiceTarget() = voice.setVoiceTarget(null)

    /**
     * Switches the audio self-test on or off (see [LoopbackMode]). The mode is
     * session-scoped and never persisted: a forgotten self-test would silently
     * stop the user from being heard.
     */
    fun setLoopback(mode: LoopbackMode) = voice.setLoopback(mode)

    private fun sendLocalMuteDeafen() {
        val muted = voice.selfMutedValue()
        val deafened = voice.selfDeafenedValue()
        roster.updateLocalMuteDeafen(muted, deafened)
        val c = state.client ?: return
        scope.launch {
            c.sendMessage(
                MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(c.currentSession)
                    .setSelfMute(muted)
                    .setSelfDeaf(deafened)
                    .build(),
            )
        }
    }
}

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

/**
 * Read-only permission and protocol-capability queries for the live session.
 * Delegates to [SessionRoster] and the negotiated server version.
 */
internal class SessionPermissions(
    private val state: SessionState,
    private val roster: SessionRoster,
) {
    fun canAdministerChannel(channelId: Int) = roster.canAdministerChannel(channelId)
    fun canMuteUser(user: User) = roster.canMuteUser(user)
    fun canPrioritySpeaker(user: User) = roster.canPrioritySpeaker(user)
    fun canMoveInChannel(channelId: Int) = roster.canMoveInChannel(channelId)
    fun canKickUser() = roster.canKickUser()
    fun canBanUser() = roster.canBanUser()
    fun canEditRegisteredUsers() = roster.canEditRegisteredUsers()
    fun canRegisterUser(user: User) = roster.canRegisterUser(user)
    fun canTextMessage(channelId: Int) = roster.canTextMessage(channelId)
    fun canListen(channelId: Int) = roster.canListen(channelId)
    fun canWriteChannel(channelId: Int) = roster.canWriteChannel(channelId)
    fun canAddChannel(channelId: Int) = roster.canAddChannel(channelId)
    fun canMakePermanentChannel(channelId: Int) = roster.canMakePermanentChannel(channelId)
    fun canLinkChannel(channelId: Int) = roster.canLinkChannel(channelId)
    fun canTraverse(channelId: Int) = roster.canTraverse(channelId)
    fun canSpeak(channelId: Int) = roster.canSpeak(channelId)
    fun canWhisper(channelId: Int) = roster.canWhisper(channelId)
    fun mayWhisper(channelId: Int) = roster.mayWhisper(channelId)
    fun canEnter(channelId: Int) = roster.canEnter(channelId)
    fun canJoinChannel(channelId: Int) = roster.canJoinChannel(channelId)
    fun canEditAcl(channelId: Int) = roster.canEditAcl(channelId)
    fun canViewUserInfo(user: User) = roster.canViewUserInfo(user)

    fun supportsSelectiveBan(): Boolean {
        val c = state.client ?: return false
        return UserModeration.supportsSelectiveBan(c.serverVersionV2, c.serverVersionLegacy)
    }

    fun supportsChannelListen(): Boolean {
        val c = state.client ?: return false
        return UserModeration.supportsChannelListen(c.serverVersionV2, c.serverVersionLegacy)
    }

    fun canResetUserContent(): Boolean {
        val c = state.client ?: return false
        return UserModeration.canResetUserContent(
            roster.rootPermissions(),
            c.serverVersionV2,
            c.serverVersionLegacy,
        )
    }
}
