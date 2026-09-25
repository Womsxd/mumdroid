package dev.woms.mumdroid.core.model

/**
 * A `ChannelState` update translated out of the wire type, with field
 * *presence* preserved as nullability: `null` means the field was absent, not
 * "false" / "0" / "". The roster merges an update over the entry it already
 * holds, so that distinction has to survive the boundary (proto3 scalars carry
 * no presence of their own).
 */
data class ChannelUpdate(
    val channelId: Int,
    val parentId: Int? = null,
    val name: String? = null,
    val description: String? = null,
    val position: Int? = null,
    val temporary: Boolean? = null,
    val maxUsers: Int? = null,
    val isEnterRestricted: Boolean? = null,
    val canEnter: Boolean? = null,
    /** Non-null (possibly empty) replaces the existing link set. */
    val links: List<Int>? = null,
    val linksAdd: List<Int> = emptyList(),
    val linksRemove: List<Int> = emptyList(),
)

/**
 * A `UserState` update translated out of the wire type, with the same
 * presence-as-nullability rule as [ChannelUpdate].
 *
 * The `listening_channel_*` fields are applied separately from the merge, so
 * they keep their list shape here.
 */
data class UserUpdate(
    val session: Int,
    val name: String? = null,
    val userId: Int? = null,
    val channelId: Int? = null,
    val selfMute: Boolean? = null,
    val selfDeaf: Boolean? = null,
    val mute: Boolean? = null,
    val deaf: Boolean? = null,
    val suppress: Boolean? = null,
    val prioritySpeaker: Boolean? = null,
    val hash: String? = null,
    val listeningAdded: List<Int> = emptyList(),
    val listeningRemoved: List<Int> = emptyList(),
)
