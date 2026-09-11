package dev.woms.mumdroid.ui.screen

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User

/**
 * The decisions [ConnectionScreen] makes from its state, kept pure so they can
 * be unit-tested without a Compose host: which tab is which, where the local
 * user currently is, and whether the voice bar may show.
 */
internal object ConnectionScreenLogic {

    /** Channel tree tab (index into `PrimaryTabRow`). */
    const val TAB_CHANNELS = 0

    /** Chat tab (index into `PrimaryTabRow`). */
    const val TAB_CHAT = 1

    /**
     * The channel the local user currently sits in, or 0 while the roster has no
     * self entry yet (before the first `UserState` of our own session arrives).
     */
    fun localChannelId(users: List<User>): Int =
        users.firstOrNull { it.isLocalUser }?.channelId ?: 0

    /**
     * Where the chat tab sends a message that carries no `@user` / `#channel`
     * target: the channel the local user is actually in, not merely the first
     * channel in the tree. Otherwise messages go to the wrong channel and appear
     * to never be sent.
     */
    fun chatChannelId(users: List<User>, channels: List<Channel>): Int =
        users.firstOrNull { it.isLocalUser }?.channelId
            ?: channels.firstOrNull()?.id
            ?: 0

    /**
     * Whether the voice bar is shown under the current tab. It stays on the
     * channel tab, but on the chat tab it disappears while the keyboard is open:
     * the bar is exactly where the keyboard lands, and the message list it would
     * cover is what the user is typing into.
     */
    fun showVoiceControls(tab: Int, keyboardOpen: Boolean): Boolean =
        tab != TAB_CHAT || !keyboardOpen
}
