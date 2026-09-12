package dev.woms.mumdroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.data.SettingsStore
import dev.woms.mumdroid.data.db.CertificateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Compose UI facade: settings plus the server-list, user-certificate and
 * live-session collaborators.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ServerStore(application)
    private val settingsStore = SettingsStore(application)
    private val certificateStore = CertificateStore(application)
    private val userCerts = UserCertificateController(application, viewModelScope)
    private val serverList = ServerListController(store, certificateStore, viewModelScope)
    private val session = ServiceSessionController(
        application,
        viewModelScope,
        onConnected = { server -> serverList.markConnected(server) },
    )

    val servers: StateFlow<List<MumbleServer>> get() = serverList.servers
    val serverPings get() = serverList.serverPings
    val refreshingPings get() = serverList.refreshingPings
    val certificates get() = serverList.certificates
    val editingServer get() = serverList.editingServer
    val showAddDialog get() = serverList.showAddDialog
    val connectionState get() = session.connectionState

    val userCertificate: StateFlow<UserCertificate> get() = userCerts.userCertificate
    val userCertificates: StateFlow<List<UserCertificate>> get() = userCerts.userCertificates

    /** Last user-certificate generation failure message (null when none). */
    val userCertificateError: StateFlow<String?> get() = userCerts.error

    /** Live session intents, separate from settings and the server list. */
    val sessionCommands: SessionCommands get() = session

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { _settings.value = it }
        }
        serverList.startAutoPing(settings)
    }

    override fun onCleared() {
        session.release()
        serverList.stop()
        super.onCleared()
    }

    /** Re-probes every saved server (home-list pull-to-refresh). */
    fun refreshPings() = serverList.refreshPings()

    fun setOutputTarget(target: VoiceOutputTarget) = session.setOutputTarget(target)

    /** Persists a settings change and applies it to the active service. */
    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            val saved = settings.sanitized()
            settingsStore.update(saved)
            _settings.value = saved
            session.applySettings(saved)
        }
    }

    fun showAddDialog(server: MumbleServer? = null) = serverList.showAddDialog(server)
    fun dismissAddDialog() = serverList.dismissAddDialog()
    fun saveServer(name: String, host: String, port: Int, username: String, password: String) =
        serverList.saveServer(name, host, port, username, password)
    fun removeServer(server: MumbleServer) = serverList.removeServer(server)
    fun deleteCertificate(certificate: CertificateEntity) = serverList.deleteCertificate(certificate)

    fun clearUserCertificateError() = userCerts.clearError()
    fun generateUserCertificate(username: String) = userCerts.generate(username)
    fun deleteUserCertificate(fingerprint: String) = userCerts.delete(fingerprint)
    fun selectUserCertificate(fingerprint: String) = userCerts.select(fingerprint)

    /**
     * Toggles cloud backup of the user-certificate private keys. The switch is
     * reflected immediately; the DataStore flow confirms it once persisted.
     *
     * `update` rather than a read-modify-write: the other writer of [_settings]
     * is the `settingsStore.settings` collector, so a CAS can never merge this
     * toggle into a snapshot it read before that emission landed.
     */
    fun setBackupUserCertificates(enabled: Boolean) {
        _settings.update { it.copy(backupUserCertificates = enabled) }
        userCerts.setBackupEnabled(enabled)
    }

    fun importUserCertificate(
        p12Bytes: ByteArray,
        password: CharArray,
        onNeedPassword: () -> Unit = {},
        onError: (String) -> Unit,
    ) = userCerts.import(p12Bytes, password, onNeedPassword, onError)

    fun exportUserCertificate(
        fingerprint: String,
        out: java.io.OutputStream,
        password: CharArray,
        onError: (String) -> Unit,
    ) = userCerts.export(fingerprint, out, password, onError)

    fun connectTo(server: MumbleServer) = session.connectTo(server)
    fun disconnect() = session.disconnect()
    fun reconnectNow() = session.reconnectNow()
    fun acknowledgeServerRemoval() = session.acknowledgeServerRemoval()

    fun toggleSelfMute() = session.toggleSelfMute()
    fun toggleSelfDeafen() = session.toggleSelfDeafen()
    fun startTalking() = session.startTalking()
    fun stopTalking() = session.stopTalking()
    fun joinChannel(channelId: Int, accessToken: String? = null) =
        session.joinChannel(channelId, accessToken)
    fun replaceAccessTokens(tokens: List<String>) = session.replaceAccessTokens(tokens)

    fun canEditRegisteredUsers(): Boolean = session.canEditRegisteredUsers()
    fun requestUserList(clear: Boolean = true) = session.requestUserList(clear)
    fun renameRegisteredUser(userId: Int, newName: String) = session.renameRegisteredUser(userId, newName)
    fun unregisterUser(userId: Int) = session.unregisterUser(userId)
    fun requestBanList(clear: Boolean = true) = session.requestBanList(clear)
    fun replaceBanList(bans: List<BanEntry>) = session.replaceBanList(bans)

    fun moveUser(sessionId: Int, channelId: Int) = session.moveUser(sessionId, channelId)
    fun clearChannelPasswordPrompt() = session.clearChannelPasswordPrompt()
    fun updatePinnedCertificate() = session.updatePinnedCertificate()
    fun trustCertificateOnce() = session.trustCertificateOnce()
    fun rejectCertificate() = session.rejectCertificate()
    fun setLocalBlock(sessionId: Int, blocked: Boolean) = session.setLocalBlock(sessionId, blocked)
    fun setLocalIgnore(sessionId: Int, ignored: Boolean) = session.setLocalIgnore(sessionId, ignored)
    fun setRemoteMute(sessionId: Int, muted: Boolean) = session.setRemoteMute(sessionId, muted)
    fun setRemoteDeafen(sessionId: Int, deafened: Boolean) = session.setRemoteDeafen(sessionId, deafened)
    fun setPrioritySpeaker(sessionId: Int, enabled: Boolean) = session.setPrioritySpeaker(sessionId, enabled)
    fun kickUser(sessionId: Int, reason: String) = session.kickUser(sessionId, reason)
    fun banUser(
        sessionId: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) = session.banUser(sessionId, reason, banCertificate, banIp, duration)
    fun registerUser(sessionId: Int) = session.registerUser(sessionId)

    fun canAdministerChannel(channelId: Int): Boolean = session.canAdministerChannel(channelId)
    fun canMuteUser(user: User): Boolean = session.canMuteUser(user)
    fun canPrioritySpeaker(user: User): Boolean = session.canPrioritySpeaker(user)
    fun canMoveInChannel(channelId: Int): Boolean = session.canMoveInChannel(channelId)
    fun ensureChannelPermissions(channelId: Int) = session.ensureChannelPermissions(channelId)
    fun canKickUser(): Boolean = session.canKickUser()
    fun canBanUser(): Boolean = session.canBanUser()
    fun canRegisterUser(user: User): Boolean = session.canRegisterUser(user)
    fun supportsSelectiveBan(): Boolean = session.supportsSelectiveBan()
    fun requestUserStats(sessionId: Int, statsOnly: Boolean = false) =
        session.requestUserStats(sessionId, statsOnly)
    fun clearUserStats() = session.clearUserStats()
    fun sendChat(channelId: Int, text: String) = session.sendChat(channelId, text)
    fun sendPrivateChat(sessionId: Int, text: String) = session.sendPrivateChat(sessionId, text)
    fun canTextMessage(channelId: Int): Boolean = session.canTextMessage(channelId)
    fun canListen(channelId: Int): Boolean = session.canListen(channelId)
    fun supportsChannelListen(): Boolean = session.supportsChannelListen()
    fun setChannelListening(channelId: Int, listen: Boolean) =
        session.setChannelListening(channelId, listen)
    fun canWriteChannel(channelId: Int): Boolean = session.canWriteChannel(channelId)
    fun canAddChannel(channelId: Int): Boolean = session.canAddChannel(channelId)
    fun canMakePermanentChannel(channelId: Int): Boolean = session.canMakePermanentChannel(channelId)
    fun canLinkChannel(channelId: Int): Boolean = session.canLinkChannel(channelId)
    fun canTraverse(channelId: Int): Boolean = session.canTraverse(channelId)
    fun canSpeak(channelId: Int): Boolean = session.canSpeak(channelId)
    fun canWhisper(channelId: Int): Boolean = session.canWhisper(channelId)
    fun canEnter(channelId: Int): Boolean = session.canEnter(channelId)
    fun canJoinChannel(channelId: Int): Boolean = session.canJoinChannel(channelId)
    fun canEditAcl(channelId: Int): Boolean = session.canEditAcl(channelId)
    fun canViewUserInfo(user: User): Boolean = session.canViewUserInfo(user)
    fun canResetUserContent(): Boolean = session.canResetUserContent()
    fun linkChannel(targetId: Int) = session.linkChannel(targetId)
    fun unlinkChannel(targetId: Int) = session.unlinkChannel(targetId)
    fun unlinkAllChannels() = session.unlinkAllChannels()
    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String,
    ) = session.createChannel(parentId, name, description, position, temporary, maxUsers, password)
    fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String,
    ) = session.updateChannel(channelId, name, description, position, maxUsers, password)
    fun removeChannel(channelId: Int) = session.removeChannel(channelId)
    fun requestChannelDescription(channelId: Int) = session.requestChannelDescription(channelId)
    fun requestChannelAcl(channelId: Int) = session.requestChannelAcl(channelId)
    fun sendChannelAcl(snapshot: ChanAclSnapshot) = session.sendChannelAcl(snapshot)
    fun channelAclSnapshot(): ChanAclSnapshot? = session.channelAclSnapshot()
    fun aclUserNames(): AclUserNames = session.aclUserNames()
    fun queryAclUsersByName(names: List<String>) = session.queryAclUsersByName(names)
    fun queryAclUsersById(ids: List<Int>) = session.queryAclUsersById(ids)
    fun setUserComment(sessionId: Int, comment: String) = session.setUserComment(sessionId, comment)
    fun resetUserComment(sessionId: Int) = session.resetUserComment(sessionId)
    fun setUserTexture(sessionId: Int, texture: ByteArray) = session.setUserTexture(sessionId, texture)
    fun resetUserTexture(sessionId: Int) = session.resetUserTexture(sessionId)
}
