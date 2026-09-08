package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.model.ChanACL.CACHED
import dev.woms.mumdroid.core.model.ChanACL.MOVE
import dev.woms.mumdroid.core.model.ChanACL.UserId.ANY


/**
 * Official `ChanACL::Perm` bits, special user/channel IDs, and ACL group
 * names from desktop `ACL.h` / `Group.cpp` / `ACLEditor.cpp`.
 *
 * Kick/Ban/Register/Write are evaluated on the root channel (id 0), matching
 * the desktop client's `Global::pPermissions` checks for the user context menu.
 */
object ChanACL {
    const val NONE = 0x0
    const val WRITE = 0x1
    const val TRAVERSE = 0x2
    const val ENTER = 0x4
    const val SPEAK = 0x8
    const val MUTE_DEAFEN = 0x10
    const val MOVE = 0x20
    const val MAKE_CHANNEL = 0x40
    const val LINK_CHANNEL = 0x80
    const val WHISPER = 0x100
    const val TEXT_MESSAGE = 0x200
    const val MAKE_TEMP_CHANNEL = 0x400
    const val LISTEN = 0x800

    /** Root-channel only. */
    const val KICK = 0x10000
    const val BAN = 0x20000
    const val REGISTER = 0x40000
    const val SELF_REGISTER = 0x80000
    const val RESET_USER_CONTENT = 0x100000

    /** Cache marker used by Murmur `effectivePermissions`; not a grantable ACL. */
    const val CACHED = 0x8000000

    /**
     * Official `ChanACL::All`: every grantable bit, excluding [CACHED].
     */
    const val ALL =
        WRITE or TRAVERSE or ENTER or SPEAK or MUTE_DEAFEN or MOVE or
            MAKE_CHANNEL or LINK_CHANNEL or WHISPER or TEXT_MESSAGE or
            MAKE_TEMP_CHANNEL or LISTEN or KICK or BAN or REGISTER or
            SELF_REGISTER or RESET_USER_CONTENT

    /**
     * Murmur default grants before any ACL is applied:
     * `Traverse | Enter | Speak | Whisper | TextMessage | Listen`.
     */
    const val DEFAULT = TRAVERSE or ENTER or SPEAK or WHISPER or TEXT_MESSAGE or LISTEN

    /**
     * SuperUser (`user_id == 0`) effective mask: `All & ~(Speak | Whisper)`.
     */
    const val SUPERUSER_EFFECTIVE = ALL and SPEAK.inv() and WHISPER.inv()

    /**
     * Official `ChanACL::Permissions` / `unsigned int` width. `ServerSync.permissions`
     * is proto `uint64` only because of a historical oversight; the desktop
     * client does `static_cast<unsigned int>(msg.permissions())`.
     */
    const val UINT32_MASK = 0xFFFFFFFFL

    /**
     * Official `User::iId` sentinels: `-1` unregistered, `0` SuperUser.
     * An ACL with [ANY] applies to a [Group] name instead of a user.
     */
    object UserId {
        const val UNREGISTERED = -1
        const val SUPERUSER = 0
        const val ANY = -1
    }

    object ChannelId {
        const val ROOT = 0
    }

    /**
     * Official meta-group names and prefix modifiers from `Group::appliesToUser`
     * and the desktop ACL editor preset list.
     */
    object Group {
        const val NONE = "none"
        const val ALL = "all"
        const val AUTH = "auth"
        const val STRONG = "strong"
        const val IN = "in"
        const val OUT = "out"
        const val SUB = "sub"

        /** Channel creator's admin group (not a meta-group). */
        const val ADMIN = "admin"

        const val INVERT = '!'
        const val ACL_CONTEXT = '~'
        const val ACCESS_TOKEN = '#'
        const val CERT_HASH = '$'

        val META = listOf(NONE, ALL, AUTH, STRONG, IN, OUT, SUB)

        /** Desktop `ACLEditor` combo presets, including `~` context variants. */
        val EDITOR_PRESETS = listOf(ALL, AUTH, IN, SUB, OUT, "~in", "~sub", "~out")
    }

    /**
     * Official `PermissionDenied.DenyType` numeric IDs from `Mumble.proto`.
     */
    object DenyType {
        const val TEXT = 0
        const val PERMISSION = 1
        const val SUPERUSER = 2
        const val CHANNEL_NAME = 3
        const val TEXT_TOO_LONG = 4
        const val H9K = 5
        const val TEMPORARY_CHANNEL = 6
        const val MISSING_CERTIFICATE = 7
        const val USER_NAME = 8
        const val CHANNEL_FULL = 9
        const val NESTING_LIMIT = 10
        const val CHANNEL_COUNT_LIMIT = 11
        const val CHANNEL_LISTENER_LIMIT = 12
        const val USER_LISTENER_LIMIT = 13
    }

    /**
     * Desktop `msgServerSync`: keep the low 32 bits of the `uint64` field
     * (official `unsigned int` cast). Bits 32+ are dropped.
     */
    fun fromWire(bits: Long): Long = bits and UINT32_MASK

    /**
     * Widens a proto `uint32` permission field. Java protobuf exposes uint32
     * as signed [Int]; bit 31 would otherwise look negative.
     */
    fun fromProtoUInt32(bits: Int): Long = bits.toLong() and UINT32_MASK

    /** Narrows to proto `uint32` / official `unsigned int`. */
    fun toProtoUInt32(bits: Long): Int = (bits and UINT32_MASK).toInt()

    fun has(permissions: Long, bit: Int): Boolean =
        permissions and fromProtoUInt32(bit) != 0L

    fun canMuteDeafen(permissions: Long): Boolean =
        has(permissions, MUTE_DEAFEN)

    /**
     * Desktop mute / deaf / priority-speaker menu:
     * `pPermissions & (Write | MuteDeafen)`. Write implies MuteDeafen in
     * Murmur's effective permissions, so either bit is enough.
     */
    fun canMuteDeafenOrWrite(permissions: Long): Boolean =
        has(permissions, WRITE or MUTE_DEAFEN)

    /**
     * Desktop `qaUserPrioritySpeaker->setEnabled`:
     * `pPermissions & (Write | MuteDeafen)`. Unlike Mute, this applies to self.
     */
    fun canPrioritySpeaker(permissions: Long): Boolean =
        canMuteDeafenOrWrite(permissions)

    /**
     * Murmur `msgUserState` channel move: the actor needs [MOVE] on the
     * source (and typically the destination). Write implies Move in
     * effective permissions, so either bit is enough to show the action.
     */
    fun canMove(permissions: Long): Boolean =
        has(permissions, WRITE or MOVE)

    /**
     * Desktop `qaUserTextMessage` / `qaChannelSendMessage`:
     * `pPermissions & (Write | TextMessage)`.
     */
    fun canTextMessage(permissions: Long): Boolean =
        has(permissions, WRITE or TEXT_MESSAGE)

    /**
     * Desktop channel Listen: Write implies Listen in effective permissions.
     * Murmur still requires the Listen bit on the target channel.
     */
    fun canListen(permissions: Long): Boolean =
        has(permissions, WRITE or LISTEN)

    /** Desktop `qaChannelAdd`: Write, MakeChannel, or MakeTempChannel. */
    fun canAddChannel(permissions: Long): Boolean =
        has(permissions, WRITE or MAKE_CHANNEL or MAKE_TEMP_CHANNEL)

    /**
     * Permanent channels need Write or MakeChannel on the parent. Without
     * those, desktop forces the Temporary checkbox.
     */
    fun canMakePermanentChannel(permissions: Long): Boolean =
        has(permissions, WRITE or MAKE_CHANNEL)

    /** Desktop `qaChannelRemove` / `qaChannelACL` properties: Write. */
    fun canWrite(permissions: Long): Boolean =
        has(permissions, WRITE)

    /**
     * Desktop `qaChannelLink` / `qaChannelUnlink`: Write implies LinkChannel
     * in effective permissions.
     */
    fun canLinkChannel(permissions: Long): Boolean =
        has(permissions, WRITE or LINK_CHANNEL)

    /** Write implies Traverse in Murmur effective permissions. */
    fun canTraverse(permissions: Long): Boolean =
        has(permissions, WRITE or TRAVERSE)

    /** Write implies Enter in Murmur effective permissions. */
    fun canEnter(permissions: Long): Boolean =
        has(permissions, WRITE or ENTER)

    /** Desktop `qaChannelJoin`: Write | Enter. */
    fun canJoinChannel(permissions: Long): Boolean = canEnter(permissions)

    /**
     * Desktop `qaChannelACL`: Write on this channel, or Write on root
     * (`Global::pPermissions`).
     */
    fun canEditAcl(channelPermissions: Long, rootPermissions: Long): Boolean =
        canWrite(channelPermissions) || canWrite(rootPermissions)

    /**
     * Desktop `qaUserInformation`:
     * root Write|Register, channel Write|Enter, or the target is self.
     */
    fun canViewUserInfo(
        rootPermissions: Long,
        channelPermissions: Long,
        isSelf: Boolean,
    ): Boolean =
        isSelf ||
            has(rootPermissions, WRITE or REGISTER) ||
            has(channelPermissions, WRITE or ENTER)

    /**
     * Speak is **not** implied by Write (SuperUser is `All & ~(Speak|Whisper)`).
     */
    fun canSpeak(permissions: Long): Boolean =
        has(permissions, SPEAK)

    /** Whisper is not implied by Write. */
    fun canWhisper(permissions: Long): Boolean =
        has(permissions, WHISPER)

    /** Root-only: Write implies ResetUserContent in Murmur effective permissions. */
    fun canResetUserContent(permissions: Long): Boolean =
        has(permissions, WRITE or RESET_USER_CONTENT)

    /**
     * Desktop `qaUserMute->setEnabled`: MuteDeafen, and for self only when
     * already server-muted or ACL-suppressed. You cannot mute yourself, but
     * an admin can lift their own channel suppress.
     */
    fun canOfferMute(
        permissions: Long,
        isSelf: Boolean,
        muted: Boolean,
        suppressed: Boolean,
    ): Boolean = canMuteDeafenOrWrite(permissions) && (!isSelf || muted || suppressed)

    /** Desktop: `pPermissions & (Kick | Ban | Write)`. */
    fun canKick(permissions: Long): Boolean =
        has(permissions, KICK or BAN or WRITE)

    /** Desktop: `pPermissions & (Ban | Write)`. */
    fun canBan(permissions: Long): Boolean =
        has(permissions, BAN or WRITE)

    /** Desktop: `pPermissions & (SelfRegister | Write)`. */
    fun canSelfRegister(permissions: Long): Boolean =
        has(permissions, SELF_REGISTER or WRITE)

    /** Desktop: `pPermissions & (Register | Write)`. */
    fun canRegisterOthers(permissions: Long): Boolean =
        has(permissions, REGISTER or WRITE)

    /**
     * Desktop user-menu Register: unregistered, has a certificate, and the
     * matching root ACL (`SelfRegister` for self, `Register` for others).
     */
    fun canOfferRegister(
        permissions: Long,
        isSelf: Boolean,
        isRegistered: Boolean,
        hasCertificate: Boolean,
    ): Boolean {
        if (isRegistered || !hasCertificate) return false
        return if (isSelf) canSelfRegister(permissions) else canRegisterOthers(permissions)
    }
}
