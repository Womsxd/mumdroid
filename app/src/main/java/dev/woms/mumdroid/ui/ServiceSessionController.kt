package dev.woms.mumdroid.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.service.MumbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Binds [MumbleService], mirrors [ConnectionState], and forwards session
 * commands. [MainViewModel] owns settings and the server list separately.
 */
internal class ServiceSessionController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onConnected: suspend (MumbleServer) -> Unit,
) : SessionCommands {
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var service: MumbleService? = null
    private var serviceBound = false
    private var attachJob: Job? = null

    /**
     * Grace window (ms) during which the optimistic "connecting" flag set by
     * [connectTo] is kept even though no service instance exists yet (the
     * startForegroundService call is still being processed).
     */
    private val optimisticConnectGraceMs = 5_000L

    @Volatile
    private var optimisticConnectAtMs = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as MumbleService.LocalBinder).service()
            service = svc
            attachToService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            detachFromService()
            service = null
            serviceBound = false
            clearStaleConnectionState()
        }

        override fun onBindingDied(name: ComponentName) {
            detachFromService()
            service = null
            serviceBound = false
            clearStaleConnectionState()
            bindToRunningService()
        }
    }

    private val sessionLeftReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MumbleService.ACTION_SESSION_LEFT) return
            detachFromService()
            unbindService()
            clearStaleConnectionState()
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            sessionLeftReceiver,
            IntentFilter(MumbleService.ACTION_SESSION_LEFT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bindToRunningService()
    }

    fun release() {
        try {
            app.unregisterReceiver(sessionLeftReceiver)
        } catch (_: IllegalArgumentException) {
        }
        detachFromService()
        unbindService()
    }

    override fun applySettings(settings: AppSettings) {
        service?.applySettings(settings)
    }

    /**
     * Attaches if the voice service is already running. Does not start it —
     * BIND_AUTO_CREATE would spawn an empty background service.
     */
    fun bindToRunningService(autoCreate: Boolean = false) {
        if (serviceBound) return
        val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
        serviceBound = app.bindService(
            Intent(app, MumbleService::class.java),
            serviceConnection,
            flags,
        )
    }

    override fun connectTo(server: MumbleServer) {
        if (!hasMicrophonePermission()) {
            _connectionState.value = _connectionState.value.copy(
                status = app.getString(R.string.status_mic_permission),
            )
            return
        }
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
        bindToRunningService(autoCreate = true)
        scope.launch { onConnected(server) }
    }

    override fun disconnect() {
        app.startService(MumbleService.disconnectIntent(app))
        // Drop the optimistic "connecting" flag from connectTo() right away:
        // a cancelled attempt must not leave the UI believing a connect is
        // still in flight, otherwise tapping a server again would just open
        // the session screen without actually connecting.
        optimisticConnectAtMs = 0L
        _connectionState.value = _connectionState.value.copy(connecting = false)
    }

    override fun reconnectNow() {
        service?.reconnectNow()
    }

    /** Dismisses a kick/ban/ghost dialog after the service has already stopped. */
    override fun acknowledgeServerRemoval() {
        _connectionState.value = _connectionState.value.copy(serverRemoval = null)
    }

    override fun setOutputTarget(target: VoiceOutputTarget) {
        service?.voiceCommands?.setOutputTarget(target)
    }

    override fun toggleSelfMute() { service?.voiceCommands?.toggleSelfMute() }
    override fun toggleSelfDeafen() { service?.voiceCommands?.toggleSelfDeafen() }
    override fun startTalking() { service?.voiceCommands?.startTalking() }
    override fun stopTalking() { service?.voiceCommands?.stopTalking() }
    override fun joinChannel(channelId: Int, accessToken: String?) {
        service?.channelCommands?.joinChannel(channelId, accessToken = accessToken)
    }

    override fun replaceAccessTokens(tokens: List<String>) { service?.adminCommands?.replaceAccessTokens(tokens) }

    override fun canEditRegisteredUsers(): Boolean = service?.permissions?.canEditRegisteredUsers() ?: false
    override fun requestUserList(clear: Boolean) {
        service?.adminCommands?.requestUserList(clear)
    }
    override fun renameRegisteredUser(userId: Int, newName: String) { service?.adminCommands?.renameRegisteredUser(userId, newName) }
    override fun unregisterUser(userId: Int) { service?.adminCommands?.unregisterUser(userId) }
    override fun requestBanList(clear: Boolean) {
        service?.adminCommands?.requestBanList(clear)
    }
    override fun replaceBanList(bans: List<BanEntry>) { service?.adminCommands?.replaceBanList(bans) }

    /** Move [session] into [channelId] (`UserState.channel_id`). */
    override fun moveUser(session: Int, channelId: Int) { service?.channelCommands?.moveUser(session, channelId) }
    override fun clearChannelPasswordPrompt() { service?.adminCommands?.clearChannelPasswordPrompt() }

    /** Certificate-mismatch prompt resolution: update pin / trust once / reject. */
    override fun updatePinnedCertificate() { service?.updatePinnedCertificate() }
    override fun trustCertificateOnce() { service?.trustCertificateOnce() }
    override fun rejectCertificate() { service?.rejectCertificate() }

    /** Locally block/unblock another user (client-side silencing, no server action). */
    override fun setLocalBlock(session: Int, blocked: Boolean) {
        service?.moderationCommands?.setLocalBlock(session, blocked)
    }

    /** Drop another user's text messages on this device only. */
    override fun setLocalIgnore(session: Int, ignored: Boolean) {
        service?.moderationCommands?.setLocalIgnore(session, ignored)
    }

    /**
     * Server-side mute/unmute of another user (requires MuteDeafen permission).
     * Unmute also lifts channel-ACL suppress.
     */
    override fun setRemoteMute(session: Int, muted: Boolean) {
        service?.moderationCommands?.setRemoteMute(session, muted)
    }

    /** Server-side deafen/undeafen of another user (requires MuteDeafen permission). */
    override fun setRemoteDeafen(session: Int, deafened: Boolean) {
        service?.moderationCommands?.setRemoteDeafen(session, deafened)
    }

    /** Toggle `UserState.priority_speaker` (requires Write or MuteDeafen). */
    override fun setPrioritySpeaker(session: Int, enabled: Boolean) {
        service?.moderationCommands?.setPrioritySpeaker(session, enabled)
    }

    /** Kick another user (requires Kick, Ban, or Write on the root channel). */
    override fun kickUser(session: Int, reason: String) { service?.adminCommands?.kickUser(session, reason) }

    /** Ban another user (requires Ban or Write on the root channel). */
    override fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) {
        service?.adminCommands?.banUser(session, reason, banCertificate, banIp, duration)
    }

    /** Register [session] on the server (`UserState.user_id = 0`). */
    override fun registerUser(session: Int) { service?.adminCommands?.registerUser(session) }

    /** @return true when the local user may server-mute/deafen users in [channelId]. */
    override fun canAdministerChannel(channelId: Int): Boolean = service?.permissions?.canAdministerChannel(channelId) ?: false

    /**
     * Desktop Mute menu: others always (with MuteDeafen); self only to lift
     * server mute or ACL suppress.
     */
    override fun canMuteUser(user: User): Boolean = service?.permissions?.canMuteUser(user) ?: false

    override fun canPrioritySpeaker(user: User): Boolean = service?.permissions?.canPrioritySpeaker(user) ?: false

    override fun canMoveInChannel(channelId: Int): Boolean = service?.permissions?.canMoveInChannel(channelId) ?: false

    override fun ensureChannelPermissions(channelId: Int) { service?.channelCommands?.ensureChannelPermissions(channelId) }

    override fun canKickUser(): Boolean = service?.permissions?.canKickUser() ?: false

    override fun canBanUser(): Boolean = service?.permissions?.canBanUser() ?: false

    override fun canRegisterUser(user: User): Boolean =
        service?.permissions?.canRegisterUser(user) ?: false

    override fun supportsSelectiveBan(): Boolean = service?.permissions?.supportsSelectiveBan() ?: false
    override fun requestUserStats(session: Int, statsOnly: Boolean) {
        service?.adminCommands?.requestUserStats(session, statsOnly)
    }
    override fun clearUserStats() { service?.adminCommands?.clearUserStats() }
    override fun sendChat(channelId: Int, text: String) { service?.chatCommands?.sendChat(channelId, text) }
    override fun sendPrivateChat(session: Int, text: String) { service?.chatCommands?.sendPrivateChat(session, text) }

    override fun canTextMessage(channelId: Int): Boolean = service?.permissions?.canTextMessage(channelId) ?: false

    override fun canListen(channelId: Int): Boolean = service?.permissions?.canListen(channelId) ?: false

    override fun supportsChannelListen(): Boolean = service?.permissions?.supportsChannelListen() ?: false

    /** Desktop `qaChannelListen`: hear a channel without joining it. */
    override fun setChannelListening(channelId: Int, listen: Boolean) {
        service?.channelCommands?.setChannelListening(channelId, listen)
    }

    override fun canWriteChannel(channelId: Int): Boolean = service?.permissions?.canWriteChannel(channelId) ?: false

    override fun canAddChannel(channelId: Int): Boolean = service?.permissions?.canAddChannel(channelId) ?: false

    override fun canMakePermanentChannel(channelId: Int): Boolean =
        service?.permissions?.canMakePermanentChannel(channelId) ?: false

    override fun canLinkChannel(channelId: Int): Boolean = service?.permissions?.canLinkChannel(channelId) ?: false

    override fun canTraverse(channelId: Int): Boolean = service?.permissions?.canTraverse(channelId) ?: false

    override fun canSpeak(channelId: Int): Boolean = service?.permissions?.canSpeak(channelId) ?: false

    override fun canWhisper(channelId: Int): Boolean = service?.permissions?.canWhisper(channelId) ?: false

    override fun canEnter(channelId: Int): Boolean = service?.permissions?.canEnter(channelId) ?: false

    override fun canJoinChannel(channelId: Int): Boolean = service?.permissions?.canJoinChannel(channelId) ?: false

    override fun canEditAcl(channelId: Int): Boolean = service?.permissions?.canEditAcl(channelId) ?: false

    override fun canViewUserInfo(user: User): Boolean = service?.permissions?.canViewUserInfo(user) ?: false

    override fun canResetUserContent(): Boolean = service?.permissions?.canResetUserContent() ?: false

    override fun linkChannel(targetId: Int) { service?.channelCommands?.linkChannel(targetId) }

    override fun unlinkChannel(targetId: Int) { service?.channelCommands?.unlinkChannel(targetId) }

    override fun unlinkAllChannels() { service?.channelCommands?.unlinkAllChannels() }

    override fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String,
    ) {
        service?.channelCommands?.createChannel(parentId, name, description, position, temporary, maxUsers, password)
    }

    override fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String,
    ) {
        service?.channelCommands?.updateChannel(channelId, name, description, position, maxUsers, password)
    }

    override fun removeChannel(channelId: Int) { service?.channelCommands?.removeChannel(channelId) }

    override fun requestChannelDescription(channelId: Int) { service?.channelCommands?.requestChannelDescription(channelId) }

    override fun requestChannelAcl(channelId: Int) { service?.adminCommands?.requestChannelAcl(channelId) }

    override fun sendChannelAcl(snapshot: ChanAclSnapshot) {
        service?.adminCommands?.sendChannelAcl(snapshot)
    }

    override fun channelAclSnapshot(): ChanAclSnapshot? = service?.channelAcl?.value

    override fun aclUserNames(): AclUserNames = service?.aclUserNames?.value ?: AclUserNames()

    override fun queryAclUsersByName(names: List<String>) { service?.adminCommands?.queryAclUsersByName(names) }

    override fun queryAclUsersById(ids: List<Int>) { service?.adminCommands?.queryAclUsersById(ids) }

    override fun setUserComment(session: Int, comment: String) {
        service?.adminCommands?.setUserComment(session, comment)
    }

    override fun resetUserComment(session: Int) { service?.adminCommands?.resetUserComment(session) }

    override fun setUserTexture(session: Int, texture: ByteArray) {
        service?.adminCommands?.setUserTexture(session, texture)
    }

    override fun resetUserTexture(session: Int) { service?.adminCommands?.resetUserTexture(session) }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun unbindService() {
        if (!serviceBound) return
        try {
            app.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
        }
        serviceBound = false
        service = null
    }

    private fun attachToService(svc: MumbleService) {
        attachJob?.cancel()
        attachJob = scope.launch {
            _connectionState.value = snapshotFrom(svc)
            coroutineScope {
                val collectors = bindServiceFlows(svc)
                while (isActive && service === svc) {
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
                collectors.forEach { it.cancel() }
            }
        }
    }

    private fun detachFromService() {
        attachJob?.cancel()
        attachJob = null
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
}

/** Starts a foreground service across API levels with an explicit component. */
private fun Context.startForegroundServiceCompat(intent: Intent) {
    // Guard against the "Service Intent must be explicit" crash: Android throws
    // IllegalArgumentException when a service is started with an implicit intent.
    // Resolve it to an explicit component before starting, if possible.
    val explicit = if (intent.component != null) {
        intent
    } else {
        val resolved = packageManager.resolveService(intent, 0)?.serviceInfo ?: return
        Intent(intent).apply {
            component = ComponentName(resolved.packageName, resolved.name)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(explicit)
    } else {
        startService(explicit)
    }
}
