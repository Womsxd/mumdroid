package dev.woms.mumdroid.ui

import dev.woms.mumdroid.service.SessionFacade
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

    fun attachTo(facade: SessionFacade, stillAttached: () -> Boolean) {
        attachJob?.cancel()
        attachJob = scope.launch {
            _state.value = snapshotFrom(facade)
            coroutineScope {
                val collectors = bindServiceFlows(facade)
                while (isActive && stillAttached()) {
                    delay(1000)
                    // Latency and UDP crypt counters change without a
                    // StateFlow emission; patch only serverInfo so the
                    // info dialog stays live without rebuilding the rest
                    // of the session snapshot.
                    if (facade.connected.value) {
                        val info = facade.connectionInfo()
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
    private fun CoroutineScope.bindServiceFlows(facade: SessionFacade): List<Job> = listOf(
        bind(facade.channels) { copy(channels = it) },
        bind(facade.users) { copy(users = it) },
        bind(facade.connected) { connected ->
            copy(
                connected = connected,
                serverInfo = facade.connectionInfo(),
                favoriteId = facade.favoriteId(),
            )
        },
        bind(facade.connecting) { copy(connecting = it) },
        bind(facade.status) { copy(status = it) },
        bind(facade.serverName) { copy(serverName = it) },
        bind(facade.selfMuted) { copy(selfMuted = it) },
        bind(facade.selfDeafened) { copy(selfDeafened = it) },
        bind(facade.chatMessages) { copy(chatMessages = it) },
        bind(facade.reconnectCountdown) { copy(reconnectCountdown = it) },
        bind(facade.reconnecting) { copy(reconnecting = it) },
        bind(facade.userStats) { copy(userInfo = it) },
        bind(facade.channelPasswordPrompt) { copy(channelPasswordPrompt = it) },
        bind(facade.certificatePrompt) { copy(certificatePrompt = it) },
        bind(facade.accessTokens) { copy(accessTokens = it) },
        bind(facade.registeredUsers) { copy(registeredUsers = it) },
        bind(facade.banList) { copy(banList = it) },
        bind(facade.userListRefreshing) { copy(userListRefreshing = it) },
        bind(facade.banListRefreshing) { copy(banListRefreshing = it) },
        bind(facade.serverRemoval) { copy(serverRemoval = it) },
        bind(facade.outputTarget) { copy(outputTarget = it) },
        bind(facade.voiceTarget) { copy(voiceTarget = it) },
        bind(facade.loopbackMode) { copy(loopbackMode = it) },
        bind(facade.permissionEpoch) { copy(permissionEpoch = it) },
        bind(facade.listeningChannels) { copy(listeningChannels = it) },
        bind(facade.channelAclPassword) { copy(channelAclPassword = it) },
    )

    private fun <T> CoroutineScope.bind(
        flow: StateFlow<T>,
        transform: ConnectionState.(T) -> ConnectionState,
    ): Job = launch {
        flow.collect { value ->
            _state.update { current -> current.transform(value) }
        }
    }

    private fun snapshotFrom(facade: SessionFacade): ConnectionState = ConnectionState(
        connected = facade.connected.value,
        connecting = facade.connecting.value,
        status = facade.status.value,
        serverName = facade.serverName.value,
        channels = facade.channels.value,
        users = facade.users.value,
        selfMuted = facade.selfMuted.value,
        selfDeafened = facade.selfDeafened.value,
        chatMessages = facade.chatMessages.value,
        reconnectCountdown = facade.reconnectCountdown.value,
        reconnecting = facade.reconnecting.value,
        serverInfo = facade.connectionInfo(),
        userInfo = facade.userStats.value,
        channelPasswordPrompt = facade.channelPasswordPrompt.value,
        certificatePrompt = facade.certificatePrompt.value,
        accessTokens = facade.accessTokens.value,
        registeredUsers = facade.registeredUsers.value,
        banList = facade.banList.value,
        userListRefreshing = facade.userListRefreshing.value,
        banListRefreshing = facade.banListRefreshing.value,
        permissionEpoch = facade.permissionEpoch.value,
        serverRemoval = facade.serverRemoval.value,
        outputTarget = facade.outputTarget.value,
        voiceTarget = facade.voiceTarget.value,
        loopbackMode = facade.loopbackMode.value,
        listeningChannels = facade.listeningChannels.value,
        channelAclPassword = facade.channelAclPassword.value,
        favoriteId = facade.favoriteId(),
    )
}
