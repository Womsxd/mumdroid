package dev.woms.mumdroid.core.model

/**
 * Channel ACL permission bits from official `ChanACL::Perm`.
 *
 * Kick/Ban/Write are evaluated on the root channel (id 0), matching the
 * desktop client's `Global::pPermissions` checks for the user context menu.
 */
object ChanACL {
    const val WRITE = 0x1
    const val ENTER = 0x4
    const val MUTE_DEAFEN = 0x10
    const val MOVE = 0x20
    const val MAKE_CHANNEL = 0x40
    const val LINK_CHANNEL = 0x80
    const val TEXT_MESSAGE = 0x200
    const val MAKE_TEMP_CHANNEL = 0x400
    const val LISTEN = 0x800
    const val KICK = 0x10000
    const val BAN = 0x20000
    const val REGISTER = 0x40000
    const val SELF_REGISTER = 0x80000

    fun canMuteDeafen(permissions: Int): Boolean =
        permissions and MUTE_DEAFEN != 0

    /**
     * Desktop mute / deaf / priority-speaker menu:
     * `pPermissions & (Write | MuteDeafen)`. Write implies MuteDeafen in
     * Murmur's effective permissions, so either bit is enough.
     */
    fun canMuteDeafenOrWrite(permissions: Int): Boolean =
        permissions and (WRITE or MUTE_DEAFEN) != 0

    /**
     * Desktop `qaUserPrioritySpeaker->setEnabled`:
     * `pPermissions & (Write | MuteDeafen)`. Unlike Mute, this applies to self.
     */
    fun canPrioritySpeaker(permissions: Int): Boolean =
        canMuteDeafenOrWrite(permissions)

    /**
     * Murmur `msgUserState` channel move: the actor needs [MOVE] on the
     * source (and typically the destination). Write implies Move in
     * effective permissions, so either bit is enough to show the action.
     */
    fun canMove(permissions: Int): Boolean =
        permissions and (WRITE or MOVE) != 0

    /**
     * Desktop `qaUserTextMessage` / `qaChannelSendMessage`:
     * `pPermissions & (Write | TextMessage)`.
     */
    fun canTextMessage(permissions: Int): Boolean =
        permissions and (WRITE or TEXT_MESSAGE) != 0

    /**
     * Desktop channel Listen: Write implies Listen in effective permissions.
     * Murmur still requires the Listen bit on the target channel.
     */
    fun canListen(permissions: Int): Boolean =
        permissions and (WRITE or LISTEN) != 0

    /** Desktop `qaChannelAdd`: Write, MakeChannel, or MakeTempChannel. */
    fun canAddChannel(permissions: Int): Boolean =
        permissions and (WRITE or MAKE_CHANNEL or MAKE_TEMP_CHANNEL) != 0

    /**
     * Permanent channels need Write or MakeChannel on the parent. Without
     * those, desktop forces the Temporary checkbox.
     */
    fun canMakePermanentChannel(permissions: Int): Boolean =
        permissions and (WRITE or MAKE_CHANNEL) != 0

    /** Desktop `qaChannelRemove` / `qaChannelACL` properties: Write. */
    fun canWrite(permissions: Int): Boolean =
        permissions and WRITE != 0

    /**
     * Desktop `qaChannelLink` / `qaChannelUnlink`: Write implies LinkChannel
     * in effective permissions.
     */
    fun canLinkChannel(permissions: Int): Boolean =
        permissions and (WRITE or LINK_CHANNEL) != 0

    /**
     * Desktop `qaUserMute->setEnabled`: MuteDeafen, and for self only when
     * already server-muted or ACL-suppressed. You cannot mute yourself, but
     * an admin can lift their own channel suppress.
     */
    fun canOfferMute(
        permissions: Int,
        isSelf: Boolean,
        muted: Boolean,
        suppressed: Boolean,
    ): Boolean = canMuteDeafenOrWrite(permissions) && (!isSelf || muted || suppressed)

    /** Desktop: `pPermissions & (Kick | Ban | Write)`. */
    fun canKick(permissions: Int): Boolean =
        permissions and (KICK or BAN or WRITE) != 0

    /** Desktop: `pPermissions & (Ban | Write)`. */
    fun canBan(permissions: Int): Boolean =
        permissions and (BAN or WRITE) != 0

    /** Desktop: `pPermissions & (SelfRegister | Write)`. */
    fun canSelfRegister(permissions: Int): Boolean =
        permissions and (SELF_REGISTER or WRITE) != 0

    /** Desktop: `pPermissions & (Register | Write)`. */
    fun canRegisterOthers(permissions: Int): Boolean =
        permissions and (REGISTER or WRITE) != 0

    /**
     * Desktop user-menu Register: unregistered, has a certificate, and the
     * matching root ACL (`SelfRegister` for self, `Register` for others).
     */
    fun canOfferRegister(
        permissions: Int,
        isSelf: Boolean,
        isRegistered: Boolean,
        hasCertificate: Boolean,
    ): Boolean {
        if (isRegistered || !hasCertificate) return false
        return if (isSelf) canSelfRegister(permissions) else canRegisterOthers(permissions)
    }
}
