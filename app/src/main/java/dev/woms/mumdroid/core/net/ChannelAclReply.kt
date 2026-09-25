package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.proto.ACL

/**
 * A channel ACL exactly as the server sent it, kept opaque so the session layer
 * can cache the reply and send a derived write back without naming the wire
 * type.
 *
 * It deliberately wraps the received message instead of converting it to
 * [ChanAclSnapshot]: a password write has to preserve every group and entry the
 * editor does not touch, which is what editing the received message does
 * ([ChannelPasswordAcl.apply]). A snapshot round-trip would only carry what the
 * editor models.
 */
class ChannelAclReply internal constructor(internal val message: ACL) {

    val channelId: Int get() = message.channelId

    /** The ACL editor's view of this reply. */
    fun snapshot(): ChanAclSnapshot = ChanAclWrite.fromProto(message)

    /** The channel password this ACL carries; empty when it has none. */
    fun password(): String = ChannelPasswordAcl.extractPassword(message)

    /**
     * The write that applies [password] to this ACL.
     *
     * @return null when the ACL already carries it, i.e. nothing to send.
     */
    fun withPassword(password: String): ChannelAclReply? =
        ChannelPasswordAcl.apply(message, password)?.let(::ChannelAclReply)

    companion object {
        /** Wraps a reply parsed off the wire. */
        fun fromProto(message: ACL): ChannelAclReply = ChannelAclReply(message)
    }
}
