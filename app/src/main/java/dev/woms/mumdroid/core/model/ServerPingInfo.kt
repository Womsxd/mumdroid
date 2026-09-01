package dev.woms.mumdroid.core.model

/**
 * Result of an unconnected UDP ping against a saved server, mirroring the
 * metadata official Mumble shows in the Connect dialog (RTT, users, version).
 */
data class ServerPingInfo(
    val pingMs: Int? = null,
    val users: Int? = null,
    val maxUsers: Int? = null,
    val version: String? = null,
    /**
     * True when [version] is an exact v2 value from a protobuf ping reply.
     * Legacy replies carry a 16.8.8 version the server saturates when a
     * component exceeds its field (patch 857 -> 255), so a legacy version must
     * never overwrite an exact one (mirrors the official client, which pings
     * protobuf-only once the server is known to be >= 1.5.0).
     */
    val versionPrecise: Boolean = false,
    val reachable: Boolean = false,
    /** [android.os.SystemClock.elapsedRealtime] when this snapshot was written. */
    val updatedAtMs: Long = 0L,
) {
    val probing: Boolean
        get() = !reachable && updatedAtMs == 0L

    /** Merges a new sample onto the last one. Later values win; blanks keep the old. */
    fun mergedWith(incoming: ServerPingInfo): ServerPingInfo {
        if (!incoming.reachable) {
            return copy(
                pingMs = null,
                reachable = false,
                updatedAtMs = incoming.updatedAtMs,
            )
        }
        // Version merge: an exact (v2) version wins over a possibly saturated
        // legacy version, in either arrival order. Other fields: latest wins.
        val mergedVersion: String?
        val mergedPrecise: Boolean
        when {
            incoming.version == null -> {
                mergedVersion = version
                mergedPrecise = versionPrecise
            }
            incoming.versionPrecise -> {
                mergedVersion = incoming.version
                mergedPrecise = true
            }
            versionPrecise -> {
                // Drop the saturated legacy value, keep the exact one.
                mergedVersion = version
                mergedPrecise = true
            }
            else -> {
                mergedVersion = incoming.version
                mergedPrecise = false
            }
        }
        return ServerPingInfo(
            pingMs = incoming.pingMs ?: pingMs,
            users = incoming.users ?: users,
            maxUsers = incoming.maxUsers ?: maxUsers,
            version = mergedVersion,
            versionPrecise = mergedPrecise,
            reachable = true,
            updatedAtMs = incoming.updatedAtMs,
        )
    }
}

/** Stable map key for ping results (`host:port`, host lowercased). */
fun MumbleServer.pingKey(): String = "${host.trim().lowercase()}:${port}"
