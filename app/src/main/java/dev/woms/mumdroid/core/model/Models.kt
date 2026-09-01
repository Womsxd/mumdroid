package dev.woms.mumdroid.core.model

/**
 * A Mumble server entry that the user can connect to.
 */
data class MumbleServer(
    val id: Long = 0,
    val name: String = "",
    val host: String = "",
    val port: Int = 64738,
    val username: String = "",
    val password: String = "",
    val certificateAlias: String? = null,
)

/** A channel on the server. */
data class Channel(
    val id: Int = 0,
    val parentId: Int = 0,
    val name: String = "",
    val description: String = "",
    val position: Int = 0,
    val temporary: Boolean = false,
    /** Official `ChannelState.max_users`; 0 means the server default. */
    val maxUsers: Int = 0,
    /** Official `is_enter_restricted`: an ACL denies ENTER (often a channel password). */
    val isEnterRestricted: Boolean = false,
    val canEnter: Boolean = true,
    /**
     * Direct [ChannelState.links] partners. Linking is symmetric; the UI
     * treats the connected component as linked, matching desktop `allLinks()`.
     */
    val linkedIds: Set<Int> = emptySet(),
    val children: MutableList<Channel> = mutableListOf(),
    val users: MutableList<User> = mutableListOf(),
) {
    /** Flat path from the root, e.g. "/Root/Sub". */
    val fullName: String
        get() = name
}

/** A connected user. */
data class User(
    val session: Int = 0,
    val name: String = "",
    /** Official `User::iId`; `-1` means unregistered, `0` is SuperUser. */
    val userId: Int = -1,
    var channelId: Int = 0,
    var selfMute: Boolean = false,
    var selfDeaf: Boolean = false,
    var mute: Boolean = false,
    var deaf: Boolean = false,
    /**
     * Channel-ACL suppress (`UserState.suppress`): the user lacks Speak in the
     * current channel. Shown as the green muted-mic icon. Cleared automatically
     * when they move to a channel they may speak in; admins can also lift it.
     */
    var suppress: Boolean = false,
    /**
     * Server-side priority speaker (`UserState.priority_speaker`). Shown as
     * the blue Campaign icon, same colour as server mute.
     */
    var prioritySpeaker: Boolean = false,
    val talking: Boolean = false,
    var isLocalUser: Boolean = false,
    /** Local (client-side) block: silence this user's audio on this device only. */
    var localBlock: Boolean = false,
    /**
     * Local ignore: drop this user's text messages on this device only
     * (`ClientUser::bLocalIgnore`). Shown as the purple Chat Bubble Off icon.
     */
    var localIgnore: Boolean = false,
    /** Certificate SHA-1 from `UserState.hash`; empty when the user has none. */
    val hash: String = "",
    /**
     * Desktop `ModelItem::isListener`: a proxy row in a channel this user is
     * listening to, not their actual seat.
     */
    val isChannelListener: Boolean = false,
    /** Channel this listener proxy sits in; 0 when [isChannelListener] is false. */
    val listenerChannelId: Int = 0,
) {
    /** Official `iId >= 0`: SuperUser or a registered account. */
    val isRegistered: Boolean get() = userId >= 0

    /**
     * True when this user cannot be heard: self mute/deaf, server mute/deaf,
     * or channel-ACL suppress. Desktop `AudioInput` treats these the same for
     * talk-state (no Talking indicator, no outbound speech).
     */
    val isSpeakBlocked: Boolean
        get() = mute || deaf || suppress || selfMute || selfDeaf

    /**
     * Status icons left-to-right after the name, matching desktop
     * `UserModel::data` (first declared = closest to the name).
     */
    fun visibleStatusIcons(): List<UserStatusIcon> = buildList {
        if (isChannelListener) {
            // Desktop `UserModel::data`: listeners always show the server-mute
            // icon (they cannot speak through the proxy), plus deaf flags.
            add(UserStatusIcon.SERVER_MUTE)
            if (deaf) add(UserStatusIcon.SERVER_DEAF)
            if (selfDeaf) add(UserStatusIcon.SELF_DEAF)
            return@buildList
        }
        if (prioritySpeaker) add(UserStatusIcon.PRIORITY_SPEAKER)
        if (mute) add(UserStatusIcon.SERVER_MUTE)
        if (suppress) add(UserStatusIcon.SUPPRESS)
        if (selfMute) add(UserStatusIcon.SELF_MUTE)
        if (localBlock) add(UserStatusIcon.LOCAL_MUTE)
        if (localIgnore) add(UserStatusIcon.LOCAL_IGNORE)
        if (deaf) add(UserStatusIcon.SERVER_DEAF)
        if (selfDeaf) add(UserStatusIcon.SELF_DEAF)
    }
}

/** Desktop user-row decoration order (comment / recording / auth omitted). */
enum class UserStatusIcon {
    PRIORITY_SPEAKER,
    SERVER_MUTE,
    SUPPRESS,
    SELF_MUTE,
    LOCAL_MUTE,
    LOCAL_IGNORE,
    SERVER_DEAF,
    SELF_DEAF,
}

/** An incoming/outgoing text message. */
data class ChatMessage(
    val actorSession: Int = 0,
    val actorName: String = "",
    val channelId: Int = 0,
    /** Display name of the channel a channel message was sent to / received from. */
    val channelName: String = "",
    val text: String,
    val isOutgoing: Boolean = false,
    val isSystem: Boolean = false,
    /** True when the message was a private (direct) message rather than a channel broadcast. */
    val isPrivate: Boolean = false,
    /** Session id of the remote user in a private conversation (0 for channel/system messages). */
    val targetSession: Int = 0,
    /** Display name of the private-message recipient (used for outgoing private messages). */
    val targetName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)
