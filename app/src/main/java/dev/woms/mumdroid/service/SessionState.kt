package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.net.MumbleClient
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mutable state for the current Mumble session, shared between [MumbleService]
 * and [MumbleServiceEvents]. Keeping it outside the Android Service lets the
 * event handler read and write session state without reaching into service
 * internals.
 */
internal class SessionState {
    val connected = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val serverName = MutableStateFlow("")
    val manualDisconnect = MutableStateFlow(false)
    val serverRemoval = MutableStateFlow<ServerRemoval?>(null)

    var client: MumbleClient? = null
    var host: String = ""
    var port: Int = 64738
    var serverMaxUsers: Int = 0
    var connectedServerId: Long = 0L
    var lastConnectParams: ConnectParams? = null
    var currentSettings: AppSettings = AppSettings()
    var forceTcp: Boolean = false
}
