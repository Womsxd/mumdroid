package dev.woms.mumdroid.ui

import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.model.pingKey
import dev.woms.mumdroid.core.net.ServerListPinger
import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.data.db.CertificateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Saved favorites, UDP pings and pinned server certificates.
 * [MainViewModel] keeps settings and the live session elsewhere.
 */
internal class ServerListController(
    private val store: ServerStore,
    private val certificateStore: CertificateStore,
    private val scope: CoroutineScope,
) {
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

    private val _editingServer = MutableStateFlow<MumbleServer?>(null)
    val editingServer: StateFlow<MumbleServer?> = _editingServer.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private var autoPingJob: Job? = null

    init {
        scope.launch {
            store.servers.collect { list ->
                _servers.value = list
                val keys = list.map { it.pingKey() }.toSet()
                _serverPings.update { current ->
                    keys.associateWith { current[it] ?: ServerPingInfo() }
                }
                val missing = list.filter { _serverPings.value[it.pingKey()]?.probing != false }
                pinger.pingMissing(scope, missing)
            }
        }
        scope.launch {
            pinger.busy.collect { busy ->
                if (!busy) _refreshingPings.value = false
            }
        }
        scope.launch {
            certificateStore.certificates.collect { _certificates.value = it }
        }
    }

    fun startAutoPing(settings: StateFlow<AppSettings>) {
        if (autoPingJob != null) return
        autoPingJob = scope.launch {
            settings.collectLatest { current ->
                if (!current.autoServerPing) return@collectLatest
                val intervalMs =
                    AppSettings.clampServerPingIntervalSeconds(current.serverPingIntervalSeconds) * 1000L
                while (isActive) {
                    delay(intervalMs)
                    val list = _servers.value
                    if (list.isNotEmpty()) pinger.pingAll(scope, list)
                }
            }
        }
    }

    fun refreshPings() {
        val list = _servers.value
        if (list.isEmpty()) {
            _refreshingPings.value = false
            return
        }
        _refreshingPings.value = true
        pinger.pingAll(scope, list)
        if (!pinger.busy.value) _refreshingPings.value = false
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
        scope.launch {
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
        scope.launch { store.removeServer(server) }
    }

    fun deleteCertificate(certificate: CertificateEntity) {
        scope.launch { certificateStore.delete(certificate) }
    }

    suspend fun markConnected(server: MumbleServer) {
        store.markConnected(server)
    }

    fun stop() {
        autoPingJob?.cancel()
        autoPingJob = null
        pinger.stop()
    }
}
