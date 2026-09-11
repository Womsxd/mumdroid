package dev.woms.mumdroid.ui.screen

import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User

/**
 * Which entries the user long-press menu offers, and where the dividers between
 * the groups go.
 *
 * Extracted from [UserContextMenu] because the menu is built from a dozen
 * permission predicates (`canKickUser`, `canMuteUser`, `canMoveInChannel`, …)
 * and a wrong flag either hides an action or advertises one the server will
 * reject. The dividers belong to the same decision: a group that renders nothing
 * must not leave a leading or trailing rule behind.
 *
 * A listener proxy row ([User.isChannelListener]) is deliberately excluded from
 * moderation, messaging and registration: it is a remote channel's user shown in
 * our tree, so acting on it would address the wrong seat.
 */
internal data class UserMenuVisibility(
    /** Listener proxy row: only "stop listening" and information make sense. */
    val isListener: Boolean,
    /** The user sits in a channel other than ours, so join / move apply. */
    val inOtherChannel: Boolean,
    /** Server mute or ACL suppress: the mute entry has to read "unmute". */
    val silencedByServer: Boolean,
    val showStopListening: Boolean,
    val showMoveMenu: Boolean,
    val showMoveHere: Boolean,
    val showMoveTo: Boolean,
    val showKick: Boolean,
    val showBan: Boolean,
    val showMute: Boolean,
    val showDeafen: Boolean,
    val showPrioritySpeaker: Boolean,
    val showBlockActions: Boolean,
    val showWhisper: Boolean,
    val showSendMessage: Boolean,
    val showLoopback: Boolean,
    val showRegister: Boolean,
) {
    /** The moderation submenu is worth rendering when at least one entry shows. */
    val showAdminMenu: Boolean
        get() = showKick || showBan || showMute || showDeafen || showPrioritySpeaker

    /** Divider above the moderation submenu, which follows join / move. */
    val dividerBeforeAdmin: Boolean
        get() = inOtherChannel || showMoveMenu

    /**
     * Divider above the local-only actions (block / ignore) and above whisper.
     * Both groups start with the same rule so they read as one section.
     */
    val dividerBeforeLocalActions: Boolean
        get() = inOtherChannel || showMoveMenu || showAdminMenu || showStopListening

    /** Divider before the voice-target pickers inside your own row's group. */
    val dividerBeforeVoiceTarget: Boolean
        get() = showBlockActions || showWhisper || showSendMessage

    /** Divider above the always-present user-information entry. */
    val dividerBeforeFooter: Boolean
        get() = showBlockActions || showWhisper || showSendMessage || showLoopback

    companion object {
        /**
         * Evaluates every entry flag for [user]. [showWhisper] and [showLoopback]
         * are not derived here: whisper depends on the row's own talk-state
         * handling and the self-test is only offered on the local user's row,
         * so the caller decides those two.
         */
        fun of(
            user: User,
            localChannelId: Int,
            moveDests: List<ChannelPick>,
            showWhisper: Boolean,
            showLoopback: Boolean,
            canAdministerChannel: (Int) -> Boolean,
            canMuteUser: (User) -> Boolean,
            canPrioritySpeaker: (User) -> Boolean,
            canMoveInChannel: (Int) -> Boolean,
            canKickUser: () -> Boolean,
            canBanUser: () -> Boolean,
            canRegisterUser: (User) -> Boolean,
            canTextMessage: (Int) -> Boolean,
        ): UserMenuVisibility {
            val isListener = user.isChannelListener
            val inOtherChannel = !user.isLocalUser && user.channelId != localChannelId
            val canMoveFrom = !isListener && !user.isLocalUser && canMoveInChannel(user.channelId)
            val showMoveHere = canMoveFrom && inOtherChannel && canMoveInChannel(localChannelId)
            val showMoveTo = canMoveFrom && moveDests.isNotEmpty()
            return UserMenuVisibility(
                isListener = isListener,
                inOtherChannel = inOtherChannel,
                silencedByServer = user.mute || user.suppress,
                showStopListening = isListener && user.isLocalUser,
                showMoveMenu = showMoveHere || showMoveTo,
                showMoveHere = showMoveHere,
                showMoveTo = showMoveTo,
                showKick = !isListener && !user.isLocalUser && canKickUser(),
                showBan = !isListener && !user.isLocalUser && canBanUser(),
                showMute = !isListener && canMuteUser(user),
                showDeafen = !isListener && !user.isLocalUser && canAdministerChannel(user.channelId),
                showPrioritySpeaker = !isListener && canPrioritySpeaker(user),
                showBlockActions = !user.isLocalUser && !isListener,
                showWhisper = showWhisper,
                showSendMessage = !user.isLocalUser && canTextMessage(
                    if (isListener) user.listenerChannelId else user.channelId,
                ),
                showLoopback = showLoopback,
                showRegister = !isListener && canRegisterUser(user),
            )
        }
    }
}
