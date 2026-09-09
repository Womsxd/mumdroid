package dev.woms.mumdroid.ui

import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclSnapshot

/**
 * Live-session intents (connect, roster, chat, ACL). Distinct from
 * [MainViewModel] settings/server-list state.
 */
interface SessionCommands {
    fun applySettings(settings: AppSettings)
    fun connectTo(server: MumbleServer)
    fun disconnect()
    fun reconnectNow()
    fun acknowledgeServerRemoval()
    fun setOutputTarget(target: VoiceOutputTarget)
    fun toggleSelfMute()
    fun toggleSelfDeafen()
    fun startTalking()
    fun stopTalking()
    fun joinChannel(channelId: Int, accessToken: String? = null)
    fun replaceAccessTokens(tokens: List<String>)
    fun canEditRegisteredUsers(): Boolean
    fun requestUserList(clear: Boolean = true)
    fun renameRegisteredUser(userId: Int, newName: String)
    fun unregisterUser(userId: Int)
    fun requestBanList(clear: Boolean = true)
    fun replaceBanList(bans: List<BanEntry>)
    fun moveUser(session: Int, channelId: Int)
    fun clearChannelPasswordPrompt()
    fun updatePinnedCertificate()
    fun trustCertificateOnce()
    fun rejectCertificate()
    fun setLocalBlock(session: Int, blocked: Boolean)
    fun setLocalIgnore(session: Int, ignored: Boolean)
    fun setRemoteMute(session: Int, muted: Boolean)
    fun setRemoteDeafen(session: Int, deafened: Boolean)
    fun setPrioritySpeaker(session: Int, enabled: Boolean)
    fun kickUser(session: Int, reason: String)
    fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    )
    fun registerUser(session: Int)
    fun canAdministerChannel(channelId: Int): Boolean
    fun canMuteUser(user: User): Boolean
    fun canPrioritySpeaker(user: User): Boolean
    fun canMoveInChannel(channelId: Int): Boolean
    fun ensureChannelPermissions(channelId: Int)
    fun canKickUser(): Boolean
    fun canBanUser(): Boolean
    fun canRegisterUser(user: User): Boolean
    fun supportsSelectiveBan(): Boolean
    fun requestUserStats(session: Int, statsOnly: Boolean = false)
    fun clearUserStats()
    fun sendChat(channelId: Int, text: String)
    fun sendPrivateChat(session: Int, text: String)
    fun canTextMessage(channelId: Int): Boolean
    fun canListen(channelId: Int): Boolean
    fun supportsChannelListen(): Boolean
    fun setChannelListening(channelId: Int, listen: Boolean)
    fun canWriteChannel(channelId: Int): Boolean
    fun canAddChannel(channelId: Int): Boolean
    fun canMakePermanentChannel(channelId: Int): Boolean
    fun canLinkChannel(channelId: Int): Boolean
    fun canTraverse(channelId: Int): Boolean
    fun canSpeak(channelId: Int): Boolean
    fun canWhisper(channelId: Int): Boolean
    fun canEnter(channelId: Int): Boolean
    fun canJoinChannel(channelId: Int): Boolean
    fun canEditAcl(channelId: Int): Boolean
    fun canViewUserInfo(user: User): Boolean
    fun canResetUserContent(): Boolean
    fun linkChannel(targetId: Int)
    fun unlinkChannel(targetId: Int)
    fun unlinkAllChannels()
    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String,
    )
    fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String,
    )
    fun removeChannel(channelId: Int)
    fun requestChannelDescription(channelId: Int)
    fun requestChannelAcl(channelId: Int)
    fun sendChannelAcl(snapshot: ChanAclSnapshot)
    fun channelAclSnapshot(): ChanAclSnapshot?
    fun aclUserNames(): AclUserNames
    fun queryAclUsersByName(names: List<String>)
    fun queryAclUsersById(ids: List<Int>)
    fun setUserComment(session: Int, comment: String)
    fun resetUserComment(session: Int)
    fun setUserTexture(session: Int, texture: ByteArray)
    fun resetUserTexture(session: Int)
}
