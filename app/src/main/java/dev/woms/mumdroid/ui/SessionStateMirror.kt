package dev.woms.mumdroid.ui

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
 * Mirrors a bound [MumbleService] into a single [ConnectionState] snapshot.
 *
 * Split out of [ServiceSessionController]: this class owns the flow
 * subscriptions and the periodic `serverInfo` refresh, the controller keeps the
 * command forwarding.
 */
internal class SessionStateMirror(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    internal val value: ConnectionState get() = _state.value

    internal fun set(update: ConnectionState) {
        _state.value = update
    }

    internal fun patch(transform: (ConnectionState) -> ConnectionState) {
        _state.update(transform)
    }

    private var attachJob: Job? = null

    fun attachTo(svc: MumbleService, stillAttached: () -> Boolean) {
        attachJob?.cancel()
        attachJob = scope.launch {
            _state.value = snapshotFrom(svc)
            coroutineScope {
                val collectors = bindServiceFlows(svc)
                while (isActive && stillAttached()) {
                    delay(1000)
                    // Latency and UDP crypt counters change without a
                    // StateFlow emission; patch only serverInfo so the
                    // info dialog stays live without rebuilding the rest
                    // of the session snapshot.
                    if (svc.connected.value) {
                        val info = svc.connectionInfo()
                        _state.update { current ->
                            if (current.serverInfo == info) current
                            else current.copy(serverInfo = info)
                        }
                    }
                }
                collectors.forEach { it.cancel() }
            }
        }
    }

    fun detach() {
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
        bind(svc.voiceTarget) { copy(voiceTarget = it) },
        bind(svc.loopbackMode) { copy(loopbackMode = it) },
        bind(svc.permissionEpoch) { copy(permissionEpoch = it) },
        bind(svc.listeningChannels) { copy(listeningChannels = it) },
        bind(svc.channelAclPassword) { copy(channelAclPassword = it) },
    )

    private fun <T> CoroutineScope.bind(
        flow: StateFlow<T>,
        transform: ConnectionState.(T) -> ConnectionState,
    ): Job = launch {
        flow.collect { value ->
            _state.update { current -> current.transform(value) }
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
        voiceTarget = svc.voiceTarget.value,
        loopbackMode = svc.loopbackMode.value,
        listeningChannels = svc.listeningChannels.value,
        channelAclPassword = svc.channelAclPassword.value,
        favoriteId = svc.favoriteId(),
    )
}
