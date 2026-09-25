package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.proto.UserStats

/**
 * A `UserStats` reply kept opaque so the session layer can hold it and read
 * what it needs without naming the wire type.
 */
class UserStatsReply internal constructor(internal val message: UserStats) {

    val session: Int get() = message.session

    /**
     * The connection's raw address bytes, empty when the server omitted them.
     * The ban flow needs these: murmur stores the binary address, so the
     * formatted snapshot string cannot be matched against it.
     */
    fun addressBytes(): ByteArray =
        if (message.hasAddress()) message.address.toByteArray() else ByteArray(0)

    /** The dialog snapshot, merged over [previous] when it is the same session. */
    fun toConnectionInfo(userName: String, previous: UserConnectionInfo?): UserConnectionInfo =
        UserConnectionInfo.fromProto(message, userName, previous)

    companion object {
        /** Wraps a reply parsed off the wire. */
        fun fromProto(message: UserStats): UserStatsReply = UserStatsReply(message)
    }
}
