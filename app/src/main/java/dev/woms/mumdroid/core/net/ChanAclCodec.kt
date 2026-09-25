package dev.woms.mumdroid.core.net

/**
 * Wire encodings for the `ChanACL::Permissions` bit mask.
 *
 * Permission bits travel as proto `uint32`, which Java protobuf exposes as a
 * signed [Int]; bit 31 would otherwise look negative. `ServerSync.permissions`
 * is proto `uint64` only because of a historical oversight and is cast back to
 * `unsigned int` by the desktop client.
 *
 * Kept out of [dev.woms.mumdroid.core.model.ChanACL] so the model layer only
 * names the permission vocabulary, never the transport representation.
 */
object ChanAclCodec {
    /** Official `ChanACL::Permissions` / `unsigned int` width. */
    const val UINT32_MASK = 0xFFFFFFFFL

    /** Widens a proto `uint32` permission field. */
    fun fromProtoUInt32(bits: Int): Long = bits.toLong() and UINT32_MASK

    /** Narrows to proto `uint32` / official `unsigned int`. */
    fun toProtoUInt32(bits: Long): Int = (bits and UINT32_MASK).toInt()

    /**
     * Desktop `msgServerSync`: keep the low 32 bits of the `uint64` field
     * (official `unsigned int` cast). Bits 32+ are dropped.
     */
    fun fromWire(bits: Long): Long = bits and UINT32_MASK
}
