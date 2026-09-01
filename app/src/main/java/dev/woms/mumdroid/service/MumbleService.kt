package dev.woms.mumdroid.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserModeration
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.CertificateDecision
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.ChannelAccessTokenStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.data.SettingsStore
import dev.woms.mumdroid.data.UserCertificateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns the Mumble TCP session and delegates
 * notifications, reconnect, roster, chat, admin lists, certificates,
 * channels, last-channel restore and voice to focused collaborators.
 */
class MumbleService : Service() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_ID = "server_id"

        private const val ACTION_CONNECT = "dev.woms.mumdroid.action.CONNECT"
        private const val ACTION_DISCONNECT = "dev.woms.mumdroid.action.DISCONNECT"
        private const val ACTION_RECONNECT_NOW = "dev.woms.mumdroid.action.RECONNECT_NOW"

        const val ACTION_SESSION_LEFT = "dev.woms.mumdroid.action.SESSION_LEFT"

        @Volatile
        private var instance: MumbleService? = null

        fun current(): MumbleService? = instance

        fun connectIntent(
            context: Context,
            host: String,
            port: Int,
            username: String,
            password: String,
            displayName: String = "",
            serverId: Long = 0L,
        ): Intent =
            Intent(context, MumbleService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_SERVER_NAME, displayName)
                putExtra(EXTRA_SERVER_ID, serverId)
            }

        fun disconnectIntent(context: Context): Intent =
            Intent(context, MumbleService::class.java).apply { action = ACTION_DISCONNECT }

        fun reconnectNowIntent(context: Context): Intent =
            Intent(context, MumbleService::class.java).apply { action = ACTION_RECONNECT_NOW }
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val events = MumbleServiceEvents(this)

    internal val reconnect = ReconnectController(scope)
    internal val chat = SessionChat(scope)
    internal val roster = SessionRoster(scope)
    internal val admin = ServerAdminSession(scope)
    internal val lastChannel = LastChannelSession(scope, roster)
    internal val tcpPing = TcpPingStats()
    internal val cert = CertificatePromptController()
    internal val notices = SessionNotices(
        chat,
        roster,
        { _serverName.value },
        object : SessionNotices.Strings {
            override fun getString(id: Int) = this@MumbleService.getString(id)
            override fun getString(id: Int, vararg formatArgs: Any) =
                this@MumbleService.getString(id, *formatArgs)
        },
    )
    internal lateinit var notifications: ConnectionNotifications
    internal lateinit var voice: VoiceSession
    internal lateinit var sessionChannels: SessionChannels

    internal val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    internal val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting
    internal val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    internal val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName
    internal val _manualDisconnect = MutableStateFlow(false)
    val manualDisconnect: StateFlow<Boolean> = _manualDisconnect.asStateFlow()
    internal val _serverRemoval = MutableStateFlow<ServerRemoval?>(null)
    val serverRemoval: StateFlow<ServerRemoval?> = _serverRemoval

    val outputTarget: StateFlow<VoiceOutputTarget?> get() = voice.outputTarget
    val channels: StateFlow<List<Channel>> get() = roster.channels
    val users: StateFlow<List<User>> get() = roster.users
    val permissionEpoch: StateFlow<Int> get() = roster.permissionEpoch
    val listeningChannels: StateFlow<Set<Int>> get() = roster.listeningChannels
    val channelAclPassword get() = admin.channelAclPassword
    val selfMuted: StateFlow<Boolean> get() = voice.selfMuted
    val selfDeafened: StateFlow<Boolean> get() = voice.selfDeafened
    val talking: StateFlow<Boolean> get() = voice.talking
    val vadLevel: StateFlow<Int> get() = voice.vadLevel
    val chatMessages get() = chat.messages
    val reconnectCountdown: StateFlow<Int> get() = reconnect.countdown
    val reconnecting: StateFlow<Boolean> get() = reconnect.reconnecting
    val userStats get() = admin.userStats
    val channelPasswordPrompt get() = admin.channelPasswordPrompt
    val certificatePrompt get() = cert.prompt
    val accessTokens get() = admin.accessTokens
    val registeredUsers get() = admin.registeredUsers
    val banList get() = admin.banList
    val userListRefreshing get() = admin.userListRefreshing
    val banListRefreshing get() = admin.banListRefreshing

    internal lateinit var settingsStore: SettingsStore
    internal lateinit var certificateStore: CertificateStore
    private lateinit var userCertificateStore: UserCertificateStore
    internal lateinit var channelAccessTokenStore: ChannelAccessTokenStore
    private lateinit var serverStore: ServerStore

    internal var currentSettings = AppSettings()
    internal var forceTcp = false
    internal var lastConnectParams: ConnectParams? = null
    internal var connectedServerId: Long = 0L
    internal var client: MumbleClient? = null
    internal var host = ""
    internal var port = 64738
    internal var serverMaxUsers = 0

    fun favoriteId(): Long = lastConnectParams?.serverId ?: connectedServerId

    private val privateReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ConnectionNotifications.ACTION_REPLY_PRIVATE) {
                events.handlePrivateReply(intent)
            }
        }
    }

    private val voiceCallbacks = object : VoiceSession.Callbacks {
        override fun settings() = currentSettings
        override fun client() = this@MumbleService.client
        override fun localSession() = roster.localSession
        override fun forceTcp() = forceTcp
        override fun setUserTalking(session: Int, talking: Boolean) =
            roster.setUserTalking(session, talking)
        override fun isServerSpeakBlocked(): Boolean {
            val local = roster.localUser() ?: return false
            return local.mute || local.suppress || local.deaf
        }
        override fun isLocallyBlocked(session: Int) = roster.isLocallyBlocked(session)
        override fun appendSystemMessage(message: String) = notices.system(message)
        override fun updateStatus(text: String) = this@MumbleService.updateStatus(text)
        override fun updateConnectedStatus() = this@MumbleService.updateConnectedStatus()
        override fun getString(id: Int) = this@MumbleService.getString(id)
        override fun getString(id: Int, vararg formatArgs: Any) =
            this@MumbleService.getString(id, *formatArgs)
    }

    private val channelCallbacks = object : SessionChannels.Callbacks {
        override fun client() = this@MumbleService.client
        override suspend fun persistAccessToken(channelId: Int, token: String) {
            admin.persistAccessToken(channelId, token, channelAccessTokenStore, host, port)
        }
        override fun rememberChannel(channelId: Int) {
            lastChannel.remember(channelId, host, port, connectedServerId)
        }
        override fun clearRestorePending() = lastChannel.clearPending()
        override fun appendSystem(message: String) = notices.system(message)
        override fun updateConnectedStatus(channelName: String) =
            this@MumbleService.updateConnectedStatus(channelName)
        override fun serverName() = _serverName.value
        override fun getString(id: Int) = this@MumbleService.getString(id)
        override fun getString(id: Int, vararg formatArgs: Any) =
            this@MumbleService.getString(id, *formatArgs)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
        certificateStore = CertificateStore(this)
        userCertificateStore = UserCertificateStore(this)
        channelAccessTokenStore = ChannelAccessTokenStore(this)
        serverStore = ServerStore(this)
        lastChannel.attach(serverStore)
        notifications = ConnectionNotifications(this)
        voice = VoiceSession(
            requireNotNull(getSystemService(AudioManager::class.java)),
            voiceCallbacks,
        )
        sessionChannels = SessionChannels(scope, roster, admin, channelCallbacks)
        _status.value = getString(R.string.status_not_connected)
        notifications.createChannels()
        ContextCompat.registerReceiver(
            this,
            privateReplyReceiver,
            IntentFilter(ConnectionNotifications.ACTION_REPLY_PRIVATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val h = intent.getStringExtra(EXTRA_HOST) ?: ""
                val p = intent.getIntExtra(EXTRA_PORT, 64738)
                val u = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                val pw = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val name = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty().ifEmpty { h }
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0L)
                host = h
                port = p
                _serverName.value = name
                startForegroundSafe()
                reconnect.abortWaitingCountdown()
                scope.launch { connect(h, p, u, pw, name, serverId) }
            }
            ACTION_DISCONNECT -> {
                startForegroundSafe(
                    _status.value.ifEmpty { getString(R.string.notification_connecting) },
                )
                disconnect()
            }
            ACTION_RECONNECT_NOW -> {
                startForegroundSafe(
                    _status.value.ifEmpty { getString(R.string.notification_connecting) },
                )
                reconnectNow()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(text: String = getString(R.string.notification_connecting)) {
        notifications.startForegroundSafe(text, _serverName.value, reconnect.countdown.value)
    }

    private fun updateStatus(text: String) = events.updateStatus(text)

    private fun updateConnectedStatus(channelName: String = roster.localChannelName()) =
        events.updateConnectedStatus(channelName)

    private fun clearSessionState() = events.clearSessionState()

    private fun applyChannelPassword(channelId: Int, password: String) =
        events.applyChannelPassword(channelId, password)

    private fun buildConnectionStats(): MumbleClient.ConnectionStats = events.buildConnectionStats()

    internal suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        displayName: String = "",
        serverId: Long = 0L,
    ) {
        mutex.withLock {
            if (_connecting.value || _connected.value) return
            reconnect.beginConnect()
            _serverRemoval.value = null
            voice.closeTransport()
            client?.close()
            client = null
            currentSettings = settingsStore.settings.first()
            forceTcp = currentSettings.forceTcp
            voice.applyInitialSettings(currentSettings)
            this.host = host
            this.port = port
            val label = displayName.ifEmpty { lastConnectParams?.displayName.orEmpty() }.ifEmpty { host }
            lastConnectParams = ConnectParams(host, port, username, password, label, serverId)
            _manualDisconnect.value = false
            _connecting.value = true
            _serverName.value = label
            updateStatus(getString(R.string.status_connecting_to, label))
            notices.joinHintsEnabled = false
            chat.clear()
            clearSessionState()
            serverStore.importLegacyLastChannels(settingsStore.consumeLegacyLastChannels())
            settingsStore.wipeLegacyAccessTokens()
            lastChannel.prepareForConnect(host, port, serverId)
            serverMaxUsers = 0
            tcpPing.reset()

            val listener = events
            try {
                val (clientCert, clientKey) = userCertificateStore.keyStoreMaterial()
                    ?: (null to null)
                connectedServerId = serverStore.resolveId(serverId, host, port)
                if (connectedServerId > 0L) {
                    lastConnectParams = lastConnectParams?.copy(serverId = connectedServerId)
                }
                admin.setTokens(channelAccessTokenStore.tokensFor(host, port))
                admin.notePasswordJoin(null)
                admin.clearPasswordPrompt()
                val pinnedFingerprint = if (currentSettings.certificatePinning) {
                    certificateStore.pinnedFingerprint(host, port)
                } else {
                    null
                }
                val c = MumbleClient(
                    host,
                    port,
                    username,
                    password,
                    listener,
                    clientCert = clientCert,
                    clientKey = clientKey,
                    initialAccessTokens = admin.tokens(),
                    certificatePinning = currentSettings.certificatePinning,
                    pinnedFingerprint = pinnedFingerprint,
                )
                c.statsProvider = MumbleClient.StatsProvider { buildConnectionStats() }
                c.tcpPingListener = MumbleClient.TcpPingListener { rtt -> tcpPing.record(rtt) }
                c.pingStatsListener = { remoteGood, _ -> voice.evaluateUdpAvailability(remoteGood) }
                client = c
                Thread { c.connect() }.start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _connecting.value = false
                throw e
            } catch (e: Exception) {
                _connecting.value = false
                updateStatus(getString(R.string.status_connection_failed, e.message ?: ""))
            }
        }
    }

    private fun disconnect() {
        reconnect.cancel()
        scope.launch {
            mutex.withLock {
                _manualDisconnect.value = true
                reconnect.markNotReconnecting()
                lastConnectParams = null
                voice.stop()
                voice.leaveCall()
                voice.closeTransport()
                client?.close()
                client = null
                _connected.value = false
                _connecting.value = false
                reconnect.cancelAndResetAttempts()
                clearSessionState()
                admin.clearTokens()
                updateStatus(getString(R.string.status_disconnected))
                voice.clearMuteDeafen()
                tcpPing.reset()
                serverMaxUsers = 0
                voice.resetEncodeToSettings(currentSettings)
            }
            notifications.cancelChat()
            sendBroadcast(Intent(ACTION_SESSION_LEFT).setPackage(packageName))
            stopSelf()
        }
    }

    fun reconnectNow() {
        val params = lastConnectParams ?: return
        if (_manualDisconnect.value || _connected.value || _connecting.value) return
        if (!reconnect.reconnectNow {
                scope.launch {
                    if (!_connected.value && !_connecting.value && !_manualDisconnect.value) {
                        connect(
                            params.host,
                            params.port,
                            params.username,
                            params.password,
                            params.displayName,
                            params.serverId,
                        )
                    }
                }
            }
        ) return
        updateStatus(getString(R.string.status_reconnecting))
    }

    fun applySettings(settings: AppSettings) {
        val next = settings.sanitized()
        val previous = currentSettings
        currentSettings = next
        voice.applySettings(previous, next)
    }

    fun setOutputTarget(target: VoiceOutputTarget) = voice.setOutputTarget(target)

    fun toggleSelfMute() {
        val newMute = voice.toggleSelfMute()
        roster.updateLocalMuteDeafen(voice.selfMutedValue(), voice.selfDeafenedValue())
        val c = client ?: return
        scope.launch {
            c.sendMessage(
                dev.woms.mumdroid.core.net.MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(c.currentSession)
                    .setSelfMute(newMute).build(),
            )
        }
    }

    fun toggleSelfDeafen() {
        val newDeaf = voice.toggleSelfDeafen()
        roster.updateLocalMuteDeafen(voice.selfMutedValue(), voice.selfDeafenedValue())
        val c = client ?: return
        scope.launch {
            c.sendMessage(
                dev.woms.mumdroid.core.net.MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(c.currentSession)
                    .setSelfDeaf(newDeaf)
                    .setSelfMute(newDeaf).build(),
            )
        }
    }

    fun setLocalBlock(session: Int, blocked: Boolean) = roster.setLocalBlock(session, blocked)
    fun setLocalIgnore(session: Int, ignored: Boolean) = roster.setLocalIgnore(session, ignored)

    fun setRemoteMute(session: Int, muted: Boolean) {
        val c = client ?: return
        val user = roster.userMap[session]
        scope.launch {
            c.sendMessage(
                dev.woms.mumdroid.core.net.MessageType.USER_STATE,
                UserModeration.remoteMute(
                    session = session,
                    currentlyMuted = user?.mute ?: false,
                    currentlySuppressed = user?.suppress ?: false,
                    wantMuted = muted,
                ),
            )
        }
    }

    fun setPrioritySpeaker(session: Int, enabled: Boolean) {
        val c = client ?: return
        scope.launch {
            c.sendMessage(
                dev.woms.mumdroid.core.net.MessageType.USER_STATE,
                UserModeration.prioritySpeaker(session, enabled),
            )
        }
    }

    fun requestUserStats(session: Int, statsOnly: Boolean = false) =
        admin.requestUserStats(client, session, statsOnly)

    fun clearUserStats() = admin.clearUserStats()

    fun setRemoteDeafen(session: Int, deafened: Boolean) {
        val c = client ?: return
        scope.launch {
            c.sendMessage(
                dev.woms.mumdroid.core.net.MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(session)
                    .setDeaf(deafened).build(),
            )
        }
    }

    fun kickUser(session: Int, reason: String) = admin.kickUser(client, session, reason)

    fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) = admin.banUser(
        client,
        session,
        roster.userMap[session],
        reason,
        banCertificate,
        banIp,
        duration,
    )

    fun registerUser(session: Int) = admin.registerUser(client, session)

    fun canAdministerChannel(channelId: Int) = roster.canAdministerChannel(channelId)
    fun canMuteUser(user: User) = roster.canMuteUser(user)
    fun canPrioritySpeaker(user: User) = roster.canPrioritySpeaker(user)
    fun canMoveInChannel(channelId: Int) = roster.canMoveInChannel(channelId)
    fun ensureChannelPermissions(channelId: Int) = sessionChannels.ensurePermissions(channelId)
    fun canKickUser() = roster.canKickUser()
    fun canBanUser() = roster.canBanUser()
    fun canEditRegisteredUsers() = roster.canEditRegisteredUsers()
    fun canRegisterUser(user: User) = roster.canRegisterUser(user)

    fun supportsSelectiveBan(): Boolean {
        val c = client ?: return false
        return UserModeration.supportsSelectiveBan(c.serverVersionV2, c.serverVersionLegacy)
    }

    fun supportsChannelListen(): Boolean {
        val c = client ?: return false
        return UserModeration.supportsChannelListen(c.serverVersionV2, c.serverVersionLegacy)
    }

    fun canTextMessage(channelId: Int) = roster.canTextMessage(channelId)
    fun canListen(channelId: Int) = roster.canListen(channelId)
    fun canWriteChannel(channelId: Int) = roster.canWriteChannel(channelId)
    fun canAddChannel(channelId: Int) = roster.canAddChannel(channelId)
    fun canMakePermanentChannel(channelId: Int) = roster.canMakePermanentChannel(channelId)
    fun canLinkChannel(channelId: Int) = roster.canLinkChannel(channelId)

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
    ) = sessionChannels.update(channelId, name, description, position, maxUsers, password, ::applyChannelPassword)

    fun removeChannel(channelId: Int) = sessionChannels.remove(channelId)

    fun requestChannelDescription(channelId: Int) = sessionChannels.requestDescription(channelId)

    fun requestChannelAcl(channelId: Int) = admin.requestAcl(client, channelId)

    fun requestUserList(clear: Boolean = true) = admin.requestUserList(client, clear)

    fun renameRegisteredUser(userId: Int, newName: String) =
        admin.renameRegisteredUser(client, userId, newName)

    fun unregisterUser(userId: Int) = admin.unregisterUser(client, userId)

    fun requestBanList(clear: Boolean = true) = admin.requestBanList(client, clear)

    fun replaceBanList(bans: List<BanEntry>) = admin.replaceBanList(client, bans)

    fun startTalking() = voice.startTalking()
    fun stopTalking() = voice.stopTalking()

    fun joinChannel(channelId: Int, announceMove: Boolean = true, accessToken: String? = null) =
        sessionChannels.join(channelId, announceMove, accessToken)

    fun moveUser(session: Int, channelId: Int) = sessionChannels.moveUser(session, channelId)

    fun clearChannelPasswordPrompt() = admin.clearPasswordPrompt()

    fun updatePinnedCertificate() = resolveCertificatePrompt(CertificateDecision.UPDATE_PIN)
    fun trustCertificateOnce() = resolveCertificatePrompt(CertificateDecision.TRUST_ONCE)
    fun rejectCertificate() = resolveCertificatePrompt(CertificateDecision.REJECT)

    private fun resolveCertificatePrompt(decision: CertificateDecision) {
        val (prompt, respond) = cert.consume() ?: return
        if (decision == CertificateDecision.UPDATE_PIN) {
            scope.launch {
                certificateStore.replaceForHost(prompt.host, prompt.port, prompt.fingerprint)
            }
        }
        respond(decision)
    }

    fun replaceAccessTokens(tokens: List<String>) {
        admin.replaceAccessTokens(tokens, channelAccessTokenStore, host, port, client)
    }

    fun sendChat(channelId: Int, text: String) {
        chat.sendToChannel(client, channelId, text, serverName.value, roster.channelName(channelId))
    }

    fun sendPrivateChat(session: Int, text: String) {
        val targetName = roster.userMap[session]?.name ?: session.toString()
        chat.sendToUser(client, session, text, serverName.value, targetName)
    }

    fun setChannelListening(channelId: Int, listen: Boolean) = sessionChannels.setListening(channelId, listen)

    fun connectionInfo(): ServerConnectionInfo = buildServerConnectionInfo(
        live = _connected.value,
        host = host,
        port = port,
        userCount = roster.userMap.size,
        maxUsers = serverMaxUsers,
        client = client,
        voice = voice,
        tcp = tcpPing,
        forceTcp = forceTcp,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(privateReplyReceiver)
        } catch (_: IllegalArgumentException) {
        }
        if (::notifications.isInitialized) notifications.cancelChat()
        reconnect.cancel()
        if (::voice.isInitialized) {
            voice.stop()
            voice.leaveCall()
            voice.closeTransport()
        }
        client?.close()
        client = null
        scope.cancel()
    }
}
