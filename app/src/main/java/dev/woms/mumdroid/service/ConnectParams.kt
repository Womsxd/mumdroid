package dev.woms.mumdroid.service

/** Parameters of a connection attempt, kept so auto-reconnect can retry. */
internal data class ConnectParams(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val displayName: String,
    val serverId: Long = 0L,
)
