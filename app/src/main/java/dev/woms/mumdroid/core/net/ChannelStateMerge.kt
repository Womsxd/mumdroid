package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.ChannelUpdate
import dev.woms.mumdroid.core.proto.ChannelState

/**
 * Translates a `ChannelState` into a [ChannelUpdate], keeping the protobuf
 * field presence the roster merge needs: `has…()` becomes nullability and
 * `links_count > 0` becomes a non-null link list (an explicitly empty `links`
 * field is treated as absent, exactly as the merge did before).
 */
object ChannelStateMerge {

    fun toUpdate(state: ChannelState): ChannelUpdate =
        ChannelUpdate(
            channelId = state.channelId,
            parentId = if (state.hasParent()) state.parent else null,
            name = if (state.hasName()) state.name else null,
            description = if (state.hasDescription()) state.description else null,
            position = if (state.hasPosition()) state.position else null,
            temporary = if (state.hasTemporary()) state.temporary else null,
            maxUsers = if (state.hasMaxUsers()) state.maxUsers else null,
            isEnterRestricted = if (state.hasIsEnterRestricted()) state.isEnterRestricted else null,
            canEnter = if (state.hasCanEnter()) state.canEnter else null,
            links = if (state.linksCount > 0) state.linksList.toList() else null,
            linksAdd = state.linksAddList.toList(),
            linksRemove = state.linksRemoveList.toList(),
        )
}
