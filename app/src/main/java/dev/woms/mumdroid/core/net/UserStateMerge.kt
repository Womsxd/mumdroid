package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserUpdate
import dev.woms.mumdroid.core.proto.UserState

/**
 * Desktop `MainWindow::msgUserState` rules for applying a roster update, plus
 * the translation of the wire message into the presence-preserving
 * [UserUpdate] the roster merges.
 */
object UserStateMerge {
    /**
     * Session 0 is the protobuf default when the field is omitted — not a
     * connected user.
     */
    fun hasValidSession(msg: UserState): Boolean =
        msg.hasSession() && msg.session != 0

    /**
     * Translates a `UserState` into a [UserUpdate], keeping `has…()` as
     * nullability.
     *
     * @return null when the message names no usable session, so the caller can
     *   skip it without knowing why.
     */
    fun toUpdate(msg: UserState): UserUpdate? {
        if (!hasValidSession(msg)) return null
        return UserUpdate(
            session = msg.session,
            name = if (msg.hasName()) msg.name else null,
            userId = if (msg.hasUserId()) msg.userId else null,
            channelId = if (msg.hasChannelId()) msg.channelId else null,
            selfMute = if (msg.hasSelfMute()) msg.selfMute else null,
            selfDeaf = if (msg.hasSelfDeaf()) msg.selfDeaf else null,
            mute = if (msg.hasMute()) msg.mute else null,
            deaf = if (msg.hasDeaf()) msg.deaf else null,
            suppress = if (msg.hasSuppress()) msg.suppress else null,
            prioritySpeaker = if (msg.hasPrioritySpeaker()) msg.prioritySpeaker else null,
            hash = if (msg.hasHash()) msg.hash else null,
            listeningAdded = msg.listeningChannelAddList.toList(),
            listeningRemoved = msg.listeningChannelRemoveList.toList(),
        )
    }

    /**
     * A UserState for an unknown session is a new connection and must include
     * a name (`if (!pDst) { if (!msg.has_name()) return; }`). Partial updates
     * such as mute/suppress after `UserRemove` must not create a nameless
     * ghost in the channel tree.
     */
    fun shouldApply(existing: User?, update: UserUpdate): Boolean {
        if (existing != null) return true
        return !update.name.isNullOrEmpty()
    }
}
