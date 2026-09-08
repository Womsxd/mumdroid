package dev.woms.mumdroid.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.BanEntry
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
        service?.setOutputTarget(target)
    }

    override fun toggleSelfMute() { service?.toggleSelfMute() }
    override fun toggleSelfDeafen() { service?.toggleSelfDeafen() }
    override fun startTalking() { service?.startTalking() }
    override fun stopTalking() { service?.stopTalking() }
    override fun joinChannel(channelId: Int, accessToken: String?) {
        service?.joinChannel(channelId, accessToken = accessToken)
    }

    override fun replaceAccessTokens(tokens: List<String>) { service?.replaceAccessTokens(tokens) }

    override fun canEditRegisteredUsers(): Boolean = service?.canEditRegisteredUsers() ?: false
    override fun requestUserList(clear: Boolean) {
        service?.requestUserList(clear)
    }
    override fun renameRegisteredUser(userId: Int, newName: String) { service?.renameRegisteredUser(userId, newName) }
    override fun unregisterUser(userId: Int) { service?.unregisterUser(userId) }
    override fun requestBanList(clear: Boolean) {
        service?.requestBanList(clear)
    }
    override fun replaceBanList(bans: List<BanEntry>) { service?.replaceBanList(bans) }

    /** Move [session] into [channelId] (`UserState.channel_id`). */
    override fun moveUser(session: Int, channelId: Int) { service?.moveUser(session, channelId) }
    override fun clearChannelPasswordPrompt() { service?.clearChannelPasswordPrompt() }

    /** Certificate-mismatch prompt resolution: update pin / trust once / reject. */
    override fun updatePinnedCertificate() { service?.updatePinnedCertificate() }
    override fun trustCertificateOnce() { service?.trustCertificateOnce() }
    override fun rejectCertificate() { service?.rejectCertificate() }

    /** Locally block/unblock another user (client-side silencing, no server action). */
    override fun setLocalBlock(session: Int, blocked: Boolean) {
        service?.setLocalBlock(session, blocked)
    }

    /** Drop another user's text messages on this device only. */
    override fun setLocalIgnore(session: Int, ignored: Boolean) {
        service?.setLocalIgnore(session, ignored)
    }

    /**
     * Server-side mute/unmute of another user (requires MuteDeafen permission).
     * Unmute also lifts channel-ACL suppress.
     */
    override fun setRemoteMute(session: Int, muted: Boolean) {
        service?.setRemoteMute(session, muted)
    }

    /** Server-side deafen/undeafen of another user (requires MuteDeafen permission). */
    override fun setRemoteDeafen(session: Int, deafened: Boolean) {
        service?.setRemoteDeafen(session, deafened)
    }

    /** Toggle `UserState.priority_speaker` (requires Write or MuteDeafen). */
    override fun setPrioritySpeaker(session: Int, enabled: Boolean) {
        service?.setPrioritySpeaker(session, enabled)
    }

    /** Kick another user (requires Kick, Ban, or Write on the root channel). */
    override fun kickUser(session: Int, reason: String) { service?.kickUser(session, reason) }

    /** Ban another user (requires Ban or Write on the root channel). */
    override fun banUser(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) {
        service?.banUser(session, reason, banCertificate, banIp, duration)
    }

    /** Register [session] on the server (`UserState.user_id = 0`). */
    override fun registerUser(session: Int) { service?.registerUser(session) }

    /** @return true when the local user may server-mute/deafen users in [channelId]. */
    override fun canAdministerChannel(channelId: Int): Boolean = service?.canAdministerChannel(channelId) ?: false

    /**
     * Desktop Mute menu: others always (with MuteDeafen); self only to lift
     * server mute or ACL suppress.
     */
    override fun canMuteUser(user: User): Boolean = service?.canMuteUser(user) ?: false

    override fun canPrioritySpeaker(user: User): Boolean = service?.canPrioritySpeaker(user) ?: false

    override fun canMoveInChannel(channelId: Int): Boolean = service?.canMoveInChannel(channelId) ?: false

    override fun ensureChannelPermissions(channelId: Int) { service?.ensureChannelPermissions(channelId) }

    override fun canKickUser(): Boolean = service?.canKickUser() ?: false

    override fun canBanUser(): Boolean = service?.canBanUser() ?: false

    override fun canRegisterUser(user: User): Boolean =
        service?.canRegisterUser(user) ?: false

    override fun supportsSelectiveBan(): Boolean = service?.supportsSelectiveBan() ?: false
    override fun requestUserStats(session: Int, statsOnly: Boolean) {
        service?.requestUserStats(session, statsOnly)
    }
    override fun clearUserStats() { service?.clearUserStats() }
    override fun sendChat(channelId: Int, text: String) { service?.sendChat(channelId, text) }
    override fun sendPrivateChat(session: Int, text: String) { service?.sendPrivateChat(session, text) }

    override fun canTextMessage(channelId: Int): Boolean = service?.canTextMessage(channelId) ?: false

    override fun canListen(channelId: Int): Boolean = service?.canListen(channelId) ?: false

    override fun supportsChannelListen(): Boolean = service?.supportsChannelListen() ?: false

    /** Desktop `qaChannelListen`: hear a channel without joining it. */
    override fun setChannelListening(channelId: Int, listen: Boolean) {
        service?.setChannelListening(channelId, listen)
    }

    override fun canWriteChannel(channelId: Int): Boolean = service?.canWriteChannel(channelId) ?: false

    override fun canAddChannel(channelId: Int): Boolean = service?.canAddChannel(channelId) ?: false

    override fun canMakePermanentChannel(channelId: Int): Boolean =
        service?.canMakePermanentChannel(channelId) ?: false

    override fun canLinkChannel(channelId: Int): Boolean = service?.canLinkChannel(channelId) ?: false

    override fun canTraverse(channelId: Int): Boolean = service?.canTraverse(channelId) ?: false

    override fun canSpeak(channelId: Int): Boolean = service?.canSpeak(channelId) ?: false

    override fun canWhisper(channelId: Int): Boolean = service?.canWhisper(channelId) ?: false

    override fun canEnter(channelId: Int): Boolean = service?.canEnter(channelId) ?: false

    override fun canJoinChannel(channelId: Int): Boolean = service?.canJoinChannel(channelId) ?: false

    override fun canEditAcl(channelId: Int): Boolean = service?.canEditAcl(channelId) ?: false

    override fun canViewUserInfo(user: User): Boolean = service?.canViewUserInfo(user) ?: false

    override fun canResetUserContent(): Boolean = service?.canResetUserContent() ?: false

    override fun linkChannel(targetId: Int) { service?.linkChannel(targetId) }

    override fun unlinkChannel(targetId: Int) { service?.unlinkChannel(targetId) }

    override fun unlinkAllChannels() { service?.unlinkAllChannels() }

    override fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String,
    ) {
        service?.createChannel(parentId, name, description, position, temporary, maxUsers, password)
    }

    override fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String,
    ) {
        service?.updateChannel(channelId, name, description, position, maxUsers, password)
    }

    override fun removeChannel(channelId: Int) { service?.removeChannel(channelId) }

    override fun requestChannelDescription(channelId: Int) { service?.requestChannelDescription(channelId) }

    override fun requestChannelAcl(channelId: Int) { service?.requestChannelAcl(channelId) }

    override fun sendChannelAcl(snapshot: ChanAclSnapshot) {
        service?.sendChannelAcl(snapshot)
    }

    override fun channelAclSnapshot(): ChanAclSnapshot? = service?.channelAcl?.value

    override fun aclUserNames(): AclUserNames = service?.aclUserNames?.value ?: AclUserNames()

    override fun queryAclUsersByName(names: List<String>) { service?.queryAclUsersByName(names) }

    override fun queryAclUsersById(ids: List<Int>) { service?.queryAclUsersById(ids) }

    override fun setUserComment(session: Int, comment: String) {
        service?.setUserComment(session, comment)
    }

    override fun resetUserComment(session: Int) { service?.resetUserComment(session) }

    override fun setUserTexture(session: Int, texture: ByteArray) {
        service?.setUserTexture(session, texture)
    }

    override fun resetUserTexture(session: Int) { service?.resetUserTexture(session) }

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
