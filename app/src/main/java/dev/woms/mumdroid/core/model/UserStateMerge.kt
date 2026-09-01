package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.UserState

/**
 * Desktop `MainWindow::msgUserState` rules for applying a roster update.
 */
object UserStateMerge {
    /**
     * Session 0 is the protobuf default when the field is omitted — not a
     * connected user.
     */
    fun hasValidSession(msg: UserState): Boolean =
        msg.hasSession() && msg.session != 0

    /**
     * A UserState for an unknown session is a new connection and must include
     * a name (`if (!pDst) { if (!msg.has_name()) return; }`). Partial updates
     * such as mute/suppress after `UserRemove` must not create a nameless
     * ghost in the channel tree.
     */
    fun shouldApply(existing: User?, msg: UserState): Boolean {
        if (!hasValidSession(msg)) return false
        if (existing != null) return true
        return msg.hasName() && msg.name.isNotEmpty()
    }
}
