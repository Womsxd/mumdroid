package dev.woms.mumdroid.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.CertificateDecision
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.TalkState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns the Mumble TCP session: the connect / disconnect
 * lifecycle and the collaborator wiring.
 *
 * Connection state lives in [SessionState], protocol events in
 * [MumbleServiceEvents] (fed by [SessionContext]), the UI-facing commands in
 * [SessionPermissions], [ChannelCommands], [AdminCommands],
 * [UserModerationCommands], [VoiceCommands] and [ChatCommands], and the read
 * surface the UI mirrors in [SessionFacade].
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
    internal val state = SessionState()

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
        { state.serverName.value },
        object : SessionNotices.Strings {
            override fun getString(id: Int) = this@MumbleService.getString(id)
            override fun getString(id: Int, vararg formatArgs: Any) =
                this@MumbleService.getString(id, *formatArgs)
        },
    )
    internal lateinit var notifications: ConnectionNotifications
    internal lateinit var voice: VoiceSession
    internal lateinit var sessionChannels: SessionChannels
    internal lateinit var events: MumbleServiceEvents

    internal lateinit var permissions: SessionPermissions
    internal lateinit var channelCommands: ChannelCommands
    internal lateinit var adminCommands: AdminCommands
    internal lateinit var moderationCommands: UserModerationCommands
    internal lateinit var voiceCommands: VoiceCommands
    internal lateinit var chatCommands: ChatCommands

    /**
     * Read surface of the session (the StateFlows the UI mirrors plus the
     * connection snapshot). Built in [onCreate] once every collaborator exists.
     */
    internal lateinit var facade: SessionFacade

    /**
     * The facade, or null while [onCreate] has not finished wiring it.
     *
     * `onServiceConnected` can fire from the main thread before `onCreate`
     * returns when the binder is handed out right after `bindService`; reading
     * the `lateinit` field from there throws [UninitializedPropertyAccessException].
     * Callers outside the service must go through this accessor.
     */
    internal val facadeOrNull: SessionFacade?
        get() = if (::facade.isInitialized) facade else null

    internal lateinit var settingsStore: SettingsStore
    internal lateinit var certificateStore: CertificateStore
    private lateinit var userCertificateStore: UserCertificateStore
    internal lateinit var channelAccessTokenStore: ChannelAccessTokenStore
    private lateinit var serverStore: ServerStore
    private lateinit var connectionFactory: ConnectionFactory

    inner class LocalBinder : Binder() {
        fun service(): MumbleService = this@MumbleService
    }

    private val binder = LocalBinder()

    fun favoriteId(): Long = facadeOrNull?.favoriteId() ?: 0L

    private val privateReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ConnectionNotifications.ACTION_REPLY_PRIVATE) {
                events.handlePrivateReply(intent)
            }
        }
    }

    private val voiceCallbacks = object : VoiceSession.Callbacks {
        override fun settings() = state.currentSettings
        override fun client() = state.client
        override fun localSession() = roster.localSession
        override fun forceTcp() = state.forceTcp
        override fun roster() = this@MumbleService.roster
        override fun setUserTalkState(session: Int, state: TalkState) =
            roster.setUserTalkState(session, state)
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
        override fun client() = state.client
        override suspend fun persistAccessToken(channelId: Int, token: String) {
            admin.persistAccessToken(channelId, token, channelAccessTokenStore, state.host, state.port)
        }
        override fun rememberChannel(channelId: Int) {
            lastChannel.remember(channelId, state.host, state.port, state.connectedServerId)
        }
        override fun clearRestorePending() = lastChannel.clearPending()
        override fun appendSystem(message: String) = notices.system(message)
        override fun updateConnectedStatus(channelName: String) =
            this@MumbleService.updateConnectedStatus(channelName)
        override fun serverName() = state.serverName.value
        override fun getString(id: Int) = this@MumbleService.getString(id)
        override fun getString(id: Int, vararg formatArgs: Any) =
            this@MumbleService.getString(id, *formatArgs)
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        certificateStore = CertificateStore(this)
        userCertificateStore = UserCertificateStore(this)
        channelAccessTokenStore = ChannelAccessTokenStore(this)
        serverStore = ServerStore(this)
        connectionFactory = ConnectionFactory(
            userCertificateStore,
            serverStore,
            channelAccessTokenStore,
            certificateStore,
        )
        lastChannel.attach(serverStore)
        roster.onRosterPruned = { if (::voice.isInitialized) voice.refreshVoiceTarget() }
        notifications = ConnectionNotifications(this)
        voice = VoiceSession(
            requireNotNull(getSystemService(AudioManager::class.java)),
            voiceCallbacks,
        )
        sessionChannels = SessionChannels(scope, roster, admin, channelCallbacks)
        events = MumbleServiceEvents(
            context = SessionContext(
                state = state,
                scope = scope,
                roster = roster,
                admin = admin,
                voice = voice,
                chat = chat,
                notices = notices,
                reconnect = reconnect,
                cert = cert,
                lastChannel = lastChannel,
                sessionChannels = sessionChannels,
                tcpPing = tcpPing,
                notifications = notifications,
            ),
            host = object : MumbleServiceEvents.SessionHost {
                override fun getString(id: Int) = this@MumbleService.getString(id)
                override fun getString(id: Int, vararg formatArgs: Any) =
                    this@MumbleService.getString(id, *formatArgs)

                override suspend fun recordCertificate(host: String, port: Int, fingerprint: String) {
                    certificateStore.record(host, port, fingerprint)
                }

                override suspend fun persistAccessToken(channelId: Int, token: String) {
                    admin.persistAccessToken(channelId, token, channelAccessTokenStore, state.host, state.port)
                }

                override suspend fun connect(params: ConnectParams) {
                    this@MumbleService.connect(
                        params.host,
                        params.port,
                        params.username,
                        params.password,
                        params.displayName,
                        params.serverId,
                    )
                }

                override fun stopSelf() = this@MumbleService.stopSelf()
            },
        )
        permissions = SessionPermissions(state, roster)
        channelCommands = ChannelCommands(sessionChannels, events::applyChannelPassword)
        adminCommands = AdminCommands(state, roster, admin, channelAccessTokenStore)
        moderationCommands = UserModerationCommands(scope, state, roster)
        voiceCommands = VoiceCommands(scope, state, roster, voice)
        chatCommands = ChatCommands(state, roster, chat)
        // Publish the read surface before the remaining wiring: a binding
        // handed out between here and the end of onCreate must never observe
        // the lateinit field unset.
        facade = SessionFacade(
            state = state,
            roster = roster,
            admin = admin,
            voice = voice,
            chat = chat,
            reconnect = reconnect,
            cert = cert,
            tcpPing = tcpPing,
        )
        state.status.value = getString(R.string.status_not_connected)
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
                state.host = h
                state.port = p
                state.serverName.value = name
                startForegroundSafe()
                reconnect.abortWaitingCountdown()
                scope.launch { connect(h, p, u, pw, name, serverId) }
            }
            ACTION_DISCONNECT -> {
                startForegroundSafe(
                    state.status.value.ifEmpty { getString(R.string.notification_connecting) },
                )
                disconnect()
            }
            ACTION_RECONNECT_NOW -> {
                startForegroundSafe(
                    state.status.value.ifEmpty { getString(R.string.notification_connecting) },
                )
                reconnectNow()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(text: String = getString(R.string.notification_connecting)) {
        notifications.startForegroundSafe(text, state.serverName.value, reconnect.countdown.value)
    }

    private fun updateStatus(text: String) = events.updateStatus(text)

    private fun updateConnectedStatus(channelName: String = roster.localChannelName()) =
        events.updateConnectedStatus(channelName)

    private fun clearSessionState() = events.clearSessionState()

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
            if (state.connecting.value || state.connected.value) return
            reconnect.beginConnect()
            state.serverRemoval.value = null
            voice.closeTransport()
            state.client?.close()
            state.client = null
            val settings = settingsStore.settings.first()
            state.currentSettings = settings
            state.forceTcp = settings.forceTcp
            voice.applyInitialSettings(settings)
            state.host = host
            state.port = port
            val label = displayName.ifEmpty { state.lastConnectParams?.displayName.orEmpty() }
                .ifEmpty { host }
            val params = ConnectParams(host, port, username, password, label, serverId)
            state.lastConnectParams = params
            state.manualDisconnect.value = false
            state.connecting.value = true
            state.serverName.value = label
            updateStatus(getString(R.string.status_connecting_to, label))
            notices.joinHintsEnabled = false
            chat.clear()
            clearSessionState()
            serverStore.importLegacyLastChannels(settingsStore.consumeLegacyLastChannels())
            settingsStore.wipeLegacyAccessTokens()
            lastChannel.prepareForConnect(host, port, serverId)
            state.serverMaxUsers = 0
            tcpPing.reset()

            try {
                val prepared = connectionFactory.create(
                    params,
                    state.currentSettings.certificatePinning,
                    events,
                )
                state.connectedServerId = prepared.resolvedServerId
                if (state.connectedServerId > 0L) {
                    state.lastConnectParams = params.copy(serverId = state.connectedServerId)
                }
                admin.setTokens(prepared.accessTokens)
                admin.notePasswordJoin(null)
                admin.clearPasswordPrompt()
                attachClientRuntime(prepared.client)
                state.client = prepared.client
                Thread { prepared.client.connect() }.start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                state.connecting.value = false
                throw e
            } catch (e: Exception) {
                state.connecting.value = false
                updateStatus(getString(R.string.status_connection_failed, e.message ?: ""))
            }
        }
    }

    private fun attachClientRuntime(c: MumbleClient) {
        voice.attachTargetSender(c)
        c.statsProvider = MumbleClient.StatsProvider { buildConnectionStats() }
        c.tcpPingListener = MumbleClient.TcpPingListener { rtt -> tcpPing.record(rtt) }
        c.pingStatsListener = { remoteGood, _ -> voice.evaluateUdpAvailability(remoteGood) }
    }

    private fun disconnect() {
        reconnect.cancel()
        scope.launch {
            mutex.withLock {
                state.manualDisconnect.value = true
                reconnect.markNotReconnecting()
                state.lastConnectParams = null
                voice.stop()
                voice.leaveCall()
                voice.closeTransport()
                state.client?.close()
                state.client = null
                state.connected.value = false
                state.connecting.value = false
                reconnect.cancelAndResetAttempts()
                clearSessionState()
                admin.clearTokens()
                updateStatus(getString(R.string.status_disconnected))
                voice.clearMuteDeafen()
                tcpPing.reset()
                state.serverMaxUsers = 0
                voice.resetEncodeToSettings(state.currentSettings)
            }
            notifications.cancelChat()
            notifications.stopForeground()
            sendBroadcast(Intent(ACTION_SESSION_LEFT).setPackage(packageName))
            stopSelf()
        }
    }

    fun reconnectNow() {
        val params = state.lastConnectParams ?: return
        if (state.manualDisconnect.value || state.connected.value || state.connecting.value) return
        if (!reconnect.reconnectNow {
                scope.launch {
                    if (!state.connected.value && !state.connecting.value && !state.manualDisconnect.value) {
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
        val previous = state.currentSettings
        state.currentSettings = next
        voice.applySettings(previous, next)
    }

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

    fun connectionInfo(): ServerConnectionInfo? = facadeOrNull?.connectionInfo()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
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
        state.client?.close()
        state.client = null
        scope.cancel()
    }
}
