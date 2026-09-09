package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.data.ChannelAccessTokenStore

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
