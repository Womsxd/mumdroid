package dev.woms.mumdroid.core.model

/** How the server ended a session, from `UserRemove`. */
enum class ServerRemovalKind {
    KICKED,
    BANNED,
    /** Closed our session without a kick actor (ghost login, server drop of us). */
    REMOVED,
}

/**
 * A kick, ban, or server-forced removal parsed from `UserRemove`.
 *
 * Ordinary disconnects of other users (no actor) are not a [ServerRemoval];
 * those stay a channel-leave notice.
 */
data class ServerRemoval(
    val kind: ServerRemovalKind,
    val actorName: String = "",
    val reason: String = "",
    val targetName: String = "",
    val isLocal: Boolean,
)

/**
 * Classifies a Mumble `UserRemove` the way the desktop client does:
 * local session → never a network drop; actor present → kick/ban.
 */
object UserRemoveNotice {
    fun of(
        isLocal: Boolean,
        hasActor: Boolean,
        actorName: String,
        targetName: String,
        reason: String,
        banned: Boolean,
    ): ServerRemoval? {
        if (isLocal) {
            val kind = when {
                banned -> ServerRemovalKind.BANNED
                hasActor -> ServerRemovalKind.KICKED
                else -> ServerRemovalKind.REMOVED
            }
            return ServerRemoval(kind, actorName, reason, targetName, true)
        }
        if (!hasActor && !banned) return null
        return ServerRemoval(
            kind = if (banned) ServerRemovalKind.BANNED else ServerRemovalKind.KICKED,
            actorName = actorName,
            reason = reason,
            targetName = targetName,
            isLocal = false,
        )
    }
}
