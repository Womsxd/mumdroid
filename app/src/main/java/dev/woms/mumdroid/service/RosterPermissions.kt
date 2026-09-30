package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The ACL bits the server reported for each channel, and the questions the app
 * asks of them.
 *
 * The policy itself is [ChanACL]'s — a permission mask in, a boolean out — and
 * this class owns only the cache those masks are read from plus the queries
 * that feed it, which keeps it free of the rest of [SessionRoster]: the
 * predicates that need a user take the [User] as a parameter instead of looking
 * one up, and nothing here reads the channel or user tables.
 */
internal class RosterPermissions {

    private val channelPermissions = ConcurrentHashMap<Int, Long>()

    /** Bumped on every [applyPermissionQuery], so permission-derived UI re-reads. */
    private val _permissionEpoch = MutableStateFlow(0)
    val permissionEpoch: StateFlow<Int> = _permissionEpoch

    /**
     * Records the ACL bits of [channelId]. [flush] drops the cached bits of the
     * other channels first, for a reply that supersedes what is held.
     */
    fun applyPermissionQuery(channelId: Int, permissions: Long, flush: Boolean) {
        if (flush) channelPermissions.clear()
        channelPermissions[channelId] = permissions
        _permissionEpoch.value++
    }

    fun permissions(channelId: Int): Long = channelPermissions[channelId] ?: 0L

    fun hasPermissions(channelId: Int): Boolean = channelPermissions.containsKey(channelId)

    fun rootPermissions(): Long = permissions(ChanACL.ChannelId.ROOT)

    fun canAdministerChannel(channelId: Int): Boolean =
        ChanACL.canMuteDeafenOrWrite(permissions(channelId))

    fun canMuteUser(user: User): Boolean =
        ChanACL.canOfferMute(
            permissions(user.channelId),
            isSelf = user.isLocalUser,
            muted = user.mute,
            suppressed = user.suppress,
        )

    fun canPrioritySpeaker(user: User): Boolean =
        ChanACL.canPrioritySpeaker(permissions(user.channelId))

    fun canMoveInChannel(channelId: Int): Boolean =
        ChanACL.canMove(permissions(channelId))

    fun canKickUser(): Boolean = ChanACL.canKick(rootPermissions())

    fun canBanUser(): Boolean = ChanACL.canBan(rootPermissions())

    fun canEditRegisteredUsers(): Boolean = ChanACL.canRegisterOthers(rootPermissions())

    fun canRegisterUser(user: User): Boolean =
        ChanACL.canOfferRegister(
            rootPermissions(),
            isSelf = user.isLocalUser,
            isRegistered = user.isRegistered,
            hasCertificate = user.hash.isNotEmpty(),
        )

    fun canTextMessage(channelId: Int): Boolean =
        ChanACL.canTextMessage(permissions(channelId))

    fun canListen(channelId: Int): Boolean =
        ChanACL.canListen(permissions(channelId))

    fun canWriteChannel(channelId: Int): Boolean =
        ChanACL.canWrite(permissions(channelId))

    fun canAddChannel(channelId: Int): Boolean =
        ChanACL.canAddChannel(permissions(channelId))

    fun canMakePermanentChannel(channelId: Int): Boolean =
        ChanACL.canMakePermanentChannel(permissions(channelId))

    fun canLinkChannel(channelId: Int): Boolean =
        ChanACL.canLinkChannel(permissions(channelId))

    fun canTraverse(channelId: Int): Boolean =
        ChanACL.canTraverse(permissions(channelId))

    fun canSpeak(channelId: Int): Boolean =
        ChanACL.canSpeak(permissions(channelId))

    fun canWhisper(channelId: Int): Boolean =
        ChanACL.canWhisper(permissions(channelId))

    /**
     * Whether whispering to [channelId] may be offered. Unlike [canWhisper] this
     * stays true while the channel's ACL bits have not arrived yet: hiding the
     * entry until a query returns would make it pop in on every menu open, and
     * murmur validates the permission again when the target is registered.
     */
    fun mayWhisper(channelId: Int): Boolean =
        !hasPermissions(channelId) || canWhisper(channelId)

    fun canEnter(channelId: Int): Boolean =
        ChanACL.canEnter(permissions(channelId))

    fun canJoinChannel(channelId: Int): Boolean =
        ChanACL.canJoinChannel(permissions(channelId))

    fun canEditAcl(channelId: Int): Boolean =
        ChanACL.canEditAcl(permissions(channelId), rootPermissions())

    fun canViewUserInfo(user: User): Boolean =
        ChanACL.canViewUserInfo(
            rootPermissions(),
            permissions(user.channelId),
            isSelf = user.isLocalUser,
        )

    /** Drops the cached bits. The epoch keeps counting, as it is only a signal. */
    fun clear() {
        channelPermissions.clear()
    }
}
