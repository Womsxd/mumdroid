package dev.woms.mumdroid.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.CertificatePrompt
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.RegisteredUser
import dev.woms.mumdroid.core.model.pingKey
import dev.woms.mumdroid.core.net.ServerListPinger
import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.data.SettingsStore
import dev.woms.mumdroid.data.db.CertificateEntity
import dev.woms.mumdroid.service.MumbleService
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel bridging the persisted server list and the connection service state
 * to the Compose UI.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ServerStore(application)
    private val settingsStore = SettingsStore(application)
    private val certificateStore = CertificateStore(application)
    private val userCerts = UserCertificateController(application, viewModelScope)

    private val _servers = MutableStateFlow<List<MumbleServer>>(emptyList())
    val servers: StateFlow<List<MumbleServer>> = _servers.asStateFlow()

    private val _serverPings = MutableStateFlow<Map<String, ServerPingInfo>>(emptyMap())
    val serverPings: StateFlow<Map<String, ServerPingInfo>> = _serverPings.asStateFlow()

    private val _refreshingPings = MutableStateFlow(false)
    val refreshingPings: StateFlow<Boolean> = _refreshingPings.asStateFlow()

    private val pinger = ServerListPinger(
        onUpdate = { updates ->
            _serverPings.update { current ->
                val next = current.toMutableMap()
                for ((key, incoming) in updates) {
                    val old = next[key]
                    next[key] = old?.mergedWith(incoming) ?: incoming
                }
                next
            }
        },
        knownInfo = { key -> _serverPings.value[key] },
    )

    private val _certificates = MutableStateFlow<List<CertificateEntity>>(emptyList())
    val certificates: StateFlow<List<CertificateEntity>> = _certificates.asStateFlow()

    val userCertificate: StateFlow<UserCertificate> get() = userCerts.userCertificate

    val userCertificates: StateFlow<List<UserCertificate>> get() = userCerts.userCertificates

    /** Last user-certificate generation failure message (null when none). */
    val userCertificateError: StateFlow<String?> get() = userCerts.error

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _editingServer = MutableStateFlow<MumbleServer?>(null)
    val editingServer: StateFlow<MumbleServer?> = _editingServer.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    // Connection state (from the service, if running)
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val service: MumbleService?
        get() = MumbleService.current()

    /**
     * Grace window (ms) during which the optimistic "connecting" flag set by
     * [connectTo] is kept even though no service instance exists yet (the
     * startForegroundService call is still being processed).
     */
    private val optimisticConnectGraceMs = 5_000L

    @Volatile
    private var optimisticConnectAtMs = 0L

    /** Resets the UI connection state once no service is running, so a stale
     *  "connecting"/"connected" snapshot cannot block or fake a new session. */
    private fun clearStaleConnectionState() {
        if (SystemClock.elapsedRealtime() - optimisticConnectAtMs < optimisticConnectGraceMs) return
        val current = _connectionState.value
        if (current == ConnectionState()) return
        // Keep the kick/ban notice after the service stops so the session
        // screen can still show why the server closed us.
        if (current.serverRemoval != null) {
            _connectionState.value = ConnectionState(
                status = current.status,
                serverName = current.serverName,
                chatMessages = current.chatMessages,
                serverRemoval = current.serverRemoval,
            )
            return
        }
        _connectionState.value = ConnectionState()
    }

    init {
        viewModelScope.launch {
            store.servers.collect { list ->
                _servers.value = list
                val keys = list.map { it.pingKey() }.toSet()
                _serverPings.update { current ->
                    keys.associateWith { current[it] ?: ServerPingInfo() }
                }
                val missing = list.filter { _serverPings.value[it.pingKey()]?.probing != false }
                pinger.pingMissing(viewModelScope, missing)
            }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { _settings.value = it }
        }
        viewModelScope.launch {
            pinger.busy.collect { busy ->
                if (!busy) _refreshingPings.value = false
            }
        }
        viewModelScope.launch {
            _settings.collectLatest { settings ->
                if (!settings.autoServerPing) return@collectLatest
                val intervalMs =
                    AppSettings.clampServerPingIntervalSeconds(settings.serverPingIntervalSeconds) * 1000L
                while (isActive) {
                    delay(intervalMs)
                    val list = _servers.value
                    if (list.isNotEmpty()) pinger.pingAll(viewModelScope, list)
                }
            }
        }
        viewModelScope.launch {
            certificateStore.certificates.collect { _certificates.value = it }
        }
        viewModelScope.launch {
            while (isActive) {
                val svc = service
                if (svc == null) {
                    // The service is gone; drop any stale connection state so
                    // a leftover "connecting" flag cannot make the UI believe
                    // a session is still being established.
                    clearStaleConnectionState()
                    kotlinx.coroutines.delay(200)
                    continue
                }
                coroutineScope {
                    _connectionState.value = snapshotFrom(svc)
                    val collectors = bindServiceFlows(svc)
                    // Wait until this service instance is replaced (a manual
                    // disconnect stops the service; the next connect starts a
                    // new instance).
                    while (service === svc && isActive) {
                        delay(1000)
                        // Latency and UDP crypt counters change without a
                        // StateFlow emission; patch only serverInfo so the
                        // info dialog stays live without rebuilding the rest
                        // of the session snapshot.
                        if (svc.connected.value) {
                            val info = svc.connectionInfo()
                            _connectionState.update { current ->
                                if (current.serverInfo == info) current
                                else current.copy(serverInfo = info)
                            }
                        }
                    }
                    // StateFlow.collect never completes on its own: without
                    // cancelling the collectors, coroutineScope would hang on
                    // them forever and this loop could never re-attach to the
                    // new service instance — which froze the UI on the
                    // "connecting" spinner after a disconnect → reconnect.
                    collectors.forEach { it.cancel() }
                }
                // Loop around and attach to the new service instance.
            }
        }
    }

    override fun onCleared() {
        pinger.stop()
        super.onCleared()
    }

    /**
     * Mirrors each low-frequency service flow into a single field of
     * [ConnectionState]. High-frequency audio meters (`vadLevel`, local
     * `talking`) stay off this snapshot: they fire from the capture callback
     * and would otherwise rebuild the whole session UI on every frame.
     * Talking indicators in the roster come from [MumbleService.users].
     */
    private fun CoroutineScope.bindServiceFlows(svc: MumbleService): List<Job> = listOf(
        bind(svc.channels) { copy(channels = it) },
        bind(svc.users) { copy(users = it) },
        bind(svc.connected) { connected ->
            copy(
                connected = connected,
                serverInfo = svc.connectionInfo(),
                favoriteId = svc.favoriteId(),
            )
        },
        bind(svc.connecting) { copy(connecting = it) },
        bind(svc.status) { copy(status = it) },
        bind(svc.serverName) { copy(serverName = it) },
        bind(svc.selfMuted) { copy(selfMuted = it) },
        bind(svc.selfDeafened) { copy(selfDeafened = it) },
        bind(svc.chatMessages) { copy(chatMessages = it) },
        bind(svc.reconnectCountdown) { copy(reconnectCountdown = it) },
        bind(svc.reconnecting) { copy(reconnecting = it) },
        bind(svc.userStats) { copy(userInfo = it) },
        bind(svc.channelPasswordPrompt) { copy(channelPasswordPrompt = it) },
        bind(svc.certificatePrompt) { copy(certificatePrompt = it) },
        bind(svc.accessTokens) { copy(accessTokens = it) },
        bind(svc.registeredUsers) { copy(registeredUsers = it) },
        bind(svc.banList) { copy(banList = it) },
        bind(svc.userListRefreshing) { copy(userListRefreshing = it) },
        bind(svc.banListRefreshing) { copy(banListRefreshing = it) },
        bind(svc.serverRemoval) { copy(serverRemoval = it) },
        bind(svc.outputTarget) { copy(outputTarget = it) },
        bind(svc.permissionEpoch) { copy(permissionEpoch = it) },
        bind(svc.listeningChannels) { copy(listeningChannels = it) },
        bind(svc.channelAclPassword) { copy(channelAclPassword = it) },
    )

    private fun <T> CoroutineScope.bind(
        flow: StateFlow<T>,
        transform: ConnectionState.(T) -> ConnectionState,
    ): Job = launch {
        flow.collect { value ->
            _connectionState.update { current -> current.transform(value) }
        }
    }

    private fun snapshotFrom(svc: MumbleService): ConnectionState = ConnectionState(
        connected = svc.connected.value,
        connecting = svc.connecting.value,
        status = svc.status.value,
        serverName = svc.serverName.value,
        channels = svc.channels.value,
        users = svc.users.value,
        selfMuted = svc.selfMuted.value,
        selfDeafened = svc.selfDeafened.value,
        chatMessages = svc.chatMessages.value,
        reconnectCountdown = svc.reconnectCountdown.value,
        reconnecting = svc.reconnecting.value,
        serverInfo = svc.connectionInfo(),
        userInfo = svc.userStats.value,
        channelPasswordPrompt = svc.channelPasswordPrompt.value,
        certificatePrompt = svc.certificatePrompt.value,
        accessTokens = svc.accessTokens.value,
        registeredUsers = svc.registeredUsers.value,
        banList = svc.banList.value,
        userListRefreshing = svc.userListRefreshing.value,
        banListRefreshing = svc.banListRefreshing.value,
        permissionEpoch = svc.permissionEpoch.value,
        serverRemoval = svc.serverRemoval.value,
        outputTarget = svc.outputTarget.value,
        listeningChannels = svc.listeningChannels.value,
        channelAclPassword = svc.channelAclPassword.value,
        favoriteId = svc.favoriteId(),
    )

    /** Re-probes every saved server (home-list pull-to-refresh). */
    fun refreshPings() {
        val list = _servers.value
        if (list.isEmpty()) {
            _refreshingPings.value = false
            return
        }
        _refreshingPings.value = true
        pinger.pingAll(viewModelScope, list)
        if (!pinger.busy.value) _refreshingPings.value = false
    }

    fun setOutputTarget(target: VoiceOutputTarget) {
        service?.setOutputTarget(target)
    }

    /** Persists a settings change and applies it to the active service. */
    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            val saved = settings.sanitized()
            settingsStore.update(saved)
            _settings.value = saved
            service?.applySettings(saved)
        }
    }

    fun showAddDialog(server: MumbleServer? = null) {
        _editingServer.value = server
        _showAddDialog.value = true
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
        _editingServer.value = null
    }

    fun saveServer(name: String, host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            val editing = _editingServer.value
            store.saveFavorite(
                MumbleServer(
                    id = editing?.id ?: 0L,
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    certificateAlias = editing?.certificateAlias,
                ),
                editingId = editing?.id ?: 0L,
            )
            dismissAddDialog()
        }
    }

    fun removeServer(server: MumbleServer) {
        viewModelScope.launch { store.removeServer(server) }
    }

    fun deleteCertificate(certificate: CertificateEntity) {
        viewModelScope.launch { certificateStore.delete(certificate) }
    }

    fun clearUserCertificateError() = userCerts.clearError()

    fun generateUserCertificate(username: String) = userCerts.generate(username)

    /** Deletes the user client certificate with the given fingerprint. */
    fun deleteUserCertificate(fingerprint: String) = userCerts.delete(fingerprint)

    /** Selects the user certificate with the given fingerprint as the active one. */
    fun selectUserCertificate(fingerprint: String) = userCerts.select(fingerprint)

    /**
     * Imports a user certificate from a PKCS#12 (.p12/.pfx) file. On success the
     * loaded certificate replaces the current one.
     */
    fun importUserCertificate(
        p12Bytes: ByteArray,
        password: CharArray,
        onNeedPassword: () -> Unit = {},
        onError: (String) -> Unit,
    ) = userCerts.import(p12Bytes, password, onNeedPassword, onError)

    /**
     * Exports the user certificate (and private key) with the given fingerprint
     * as a PKCS#12 file.
     */
    fun exportUserCertificate(fingerprint: String, out: java.io.OutputStream, password: CharArray, onError: (String) -> Unit) =
        userCerts.export(fingerprint, out, password, onError)

    fun connectTo(server: MumbleServer) {
        if (!hasMicrophonePermission()) {
            val app = getApplication<Application>()
            _connectionState.value = _connectionState.value.copy(status = app.getString(R.string.status_mic_permission))
            return
        }
        val app = getApplication<Application>()
        val intent = MumbleService.connectIntent(
            app, server.host, server.port, server.username, server.password,
            displayName = server.name.ifEmpty { server.host },
            serverId = server.id,
        )
        // Optimistically mark the connection as in-progress so the connection
        // screen shows the spinner immediately; the service poll reconciles
        // with the real state shortly after.
        optimisticConnectAtMs = SystemClock.elapsedRealtime()
        _connectionState.value = _connectionState.value.copy(
            connecting = true,
            serverName = server.name.ifEmpty { server.host },
            serverRemoval = null,
        )
        app.startForegroundServiceCompat(intent)
        viewModelScope.launch { store.markConnected(server) }
    }

    fun disconnect() {
        val app = getApplication<Application>()
        app.startService(MumbleService.disconnectIntent(app))
        // Drop the optimistic "connecting" flag from connectTo() right away:
        // a cancelled attempt must not leave the UI believing a connect is
        // still in flight, otherwise tapping a server again would just open
        // the session screen without actually connecting.
        optimisticConnectAtMs = 0L
        _connectionState.value = _connectionState.value.copy(connecting = false)
    }

    /** Skip the auto-reconnect countdown and try to reconnect immediately. */
    fun reconnectNow() {
        service?.reconnectNow()
    }

    private fun hasMicrophonePermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun toggleSelfMute() = service?.toggleSelfMute()
    fun toggleSelfDeafen() = service?.toggleSelfDeafen()
    fun startTalking() = service?.startTalking()
    fun stopTalking() = service?.stopTalking()
    fun joinChannel(channelId: Int, accessToken: String? = null) =
        service?.joinChannel(channelId, accessToken = accessToken)

    fun replaceAccessTokens(tokens: List<String>) = service?.replaceAccessTokens(tokens)

    fun canEditRegisteredUsers(): Boolean = service?.canEditRegisteredUsers() ?: false
    fun requestUserList(clear: Boolean = true) = service?.requestUserList(clear)
    fun renameRegisteredUser(userId: Int, newName: String) = service?.renameRegisteredUser(userId, newName)
    fun unregisterUser(userId: Int) = service?.unregisterUser(userId)
    fun requestBanList(clear: Boolean = true) = service?.requestBanList(clear)
    fun replaceBanList(bans: List<BanEntry>) = service?.replaceBanList(bans)

    /** Move [session] into [channelId] (`UserState.channel_id`). */
    fun moveUser(session: Int, channelId: Int) = service?.moveUser(session, channelId)
    fun clearChannelPasswordPrompt() = service?.clearChannelPasswordPrompt()

    /** Certificate-mismatch prompt resolution: update pin / trust once / reject. */
    fun updatePinnedCertificate() = service?.updatePinnedCertificate()
    fun trustCertificateOnce() = service?.trustCertificateOnce()
    fun rejectCertificate() = service?.rejectCertificate()
    /** Locally block/unblock another user (client-side silencing, no server action). */
    fun setLocalBlock(session: Int, blocked: Boolean) = service?.setLocalBlock(session, blocked)

    /** Drop another user's text messages on this device only. */
    fun setLocalIgnore(session: Int, ignored: Boolean) = service?.setLocalIgnore(session, ignored)

    /**
     * Server-side mute/unmute of another user (requires MuteDeafen permission).
     * Unmute also lifts channel-ACL suppress.
     */
    fun setRemoteMute(session: Int, muted: Boolean) = service?.setRemoteMute(session, muted)

    /** Server-side deafen/undeafen of another user (requires MuteDeafen permission). */
    fun setRemoteDeafen(session: Int, deafened: Boolean) = service?.setRemoteDeafen(session, deafened)

    /** Toggle `UserState.priority_speaker` (requires Write or MuteDeafen). */
    fun setPrioritySpeaker(session: Int, enabled: Boolean) =
        service?.setPrioritySpeaker(session, enabled)

    /** Kick another user (requires Kick, Ban, or Write on the root channel). */
    fun kickUser(session: Int, reason: String) = service?.kickUser(session, reason)

    /** Ban another user (requires Ban or Write on the root channel). */
    fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) = service?.banUser(session, reason, banCertificate, banIp, duration)

    /** Register [session] on the server (`UserState.user_id = 0`). */
    fun registerUser(session: Int) = service?.registerUser(session)

    /** @return true when the local user may server-mute/deafen users in [channelId]. */
    fun canAdministerChannel(channelId: Int): Boolean = service?.canAdministerChannel(channelId) ?: false

    /**
     * Desktop Mute menu: others always (with MuteDeafen); self only to lift
     * server mute or ACL suppress.
     */
    fun canMuteUser(user: User): Boolean = service?.canMuteUser(user) ?: false

    fun canPrioritySpeaker(user: User): Boolean = service?.canPrioritySpeaker(user) ?: false

    fun canMoveInChannel(channelId: Int): Boolean = service?.canMoveInChannel(channelId) ?: false

    fun ensureChannelPermissions(channelId: Int) = service?.ensureChannelPermissions(channelId)

    fun canKickUser(): Boolean = service?.canKickUser() ?: false

    fun canBanUser(): Boolean = service?.canBanUser() ?: false

    fun canRegisterUser(user: User): Boolean =
        service?.canRegisterUser(user) ?: false

    fun supportsSelectiveBan(): Boolean = service?.supportsSelectiveBan() ?: false
    fun requestUserStats(session: Int, statsOnly: Boolean = false) =
        service?.requestUserStats(session, statsOnly)
    fun clearUserStats() = service?.clearUserStats()
    fun sendChat(channelId: Int, text: String) = service?.sendChat(channelId, text)
    fun sendPrivateChat(session: Int, text: String) = service?.sendPrivateChat(session, text)

    fun canTextMessage(channelId: Int): Boolean = service?.canTextMessage(channelId) ?: false

    fun canListen(channelId: Int): Boolean = service?.canListen(channelId) ?: false

    fun supportsChannelListen(): Boolean = service?.supportsChannelListen() ?: false

    /** Desktop `qaChannelListen`: hear a channel without joining it. */
    fun setChannelListening(channelId: Int, listen: Boolean) =
        service?.setChannelListening(channelId, listen)

    fun canWriteChannel(channelId: Int): Boolean = service?.canWriteChannel(channelId) ?: false

    fun canAddChannel(channelId: Int): Boolean = service?.canAddChannel(channelId) ?: false

    fun canMakePermanentChannel(channelId: Int): Boolean =
        service?.canMakePermanentChannel(channelId) ?: false

    fun canLinkChannel(channelId: Int): Boolean = service?.canLinkChannel(channelId) ?: false

    fun linkChannel(targetId: Int) = service?.linkChannel(targetId)

    fun unlinkChannel(targetId: Int) = service?.unlinkChannel(targetId)

    fun unlinkAllChannels() = service?.unlinkAllChannels()

    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String,
    ) = service?.createChannel(parentId, name, description, position, temporary, maxUsers, password)

    fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String,
    ) = service?.updateChannel(channelId, name, description, position, maxUsers, password)

    fun removeChannel(channelId: Int) = service?.removeChannel(channelId)

    fun requestChannelDescription(channelId: Int) = service?.requestChannelDescription(channelId)

    fun requestChannelAcl(channelId: Int) = service?.requestChannelAcl(channelId)

    /** Dismisses a kick/ban/ghost dialog after the service has already stopped. */
    fun acknowledgeServerRemoval() {
        _connectionState.value = _connectionState.value.copy(serverRemoval = null)
    }
}
