package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.net.UserModeration

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
