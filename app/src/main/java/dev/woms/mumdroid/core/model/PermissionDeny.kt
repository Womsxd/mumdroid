package dev.woms.mumdroid.core.model

/**
 * A server `PermissionDenied` translated out of the wire type, so the session
 * layer never names a protobuf class.
 *
 * [type] mirrors the official `DenyType` for the values we react to; the rest
 * (including the joke `H9K`) fold into [DenyType.OTHER], which is the same set
 * the wording lookup has no specific message for.
 */
data class PermissionDeny(
    val type: DenyType,
    /** Server-provided reason; empty when it sent none. */
    val reason: String,
    /** Channel the denial is about (0 when the server set none). */
    val channelId: Int,
    /** Denied permission bitmask, already widened from the wire `uint32`. */
    val permission: Long,
) {
    enum class DenyType {
        /** No specific wording (`Text`, `H9K`, unset or unknown). */
        OTHER,
        PERMISSION,
        SUPER_USER,
        CHANNEL_NAME,
        TEXT_TOO_LONG,
        TEMPORARY_CHANNEL,
        MISSING_CERTIFICATE,
        USER_NAME,
        CHANNEL_FULL,
        NESTING_LIMIT,
        CHANNEL_COUNT_LIMIT,
        CHANNEL_LISTENER_LIMIT,
        USER_LISTENER_LIMIT,
    }
}
