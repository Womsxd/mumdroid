package dev.woms.mumdroid.ui.screen

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelLinks
import dev.woms.mumdroid.core.model.ChannelTree

/** Everything the channel row needs to decide what it shows and offers. */
internal data class ChannelNodeFlags(
    val showJoin: Boolean,
    val showSend: Boolean,
    val showShout: Boolean,
    val shoutActive: Boolean,
    val showListen: Boolean,
    val listening: Boolean,
    val showAdd: Boolean,
    val showEdit: Boolean,
    val showRemove: Boolean,
    val forceTemporary: Boolean,
    val canCollapse: Boolean,
    val collapsed: Boolean,
    val isCurrentChannel: Boolean,
    val isLinked: Boolean,
    val showLinkIcon: Boolean,
)

/**
 * Pure derivation of a channel row's state: which menu entries exist, whether
 * the node folds, and how the label is styled. Extracted from [ChannelNode] so
 * the permission matrix can be exercised without a Compose runtime.
 */
internal object ChannelNodeVisibility {

    fun of(
        channel: Channel,
        collapsedIds: Set<Int>,
        localChannelId: Int,
        homeAllLinks: Set<Int>,
        homeDirectLinks: Set<Int>,
        listeningChannels: Set<Int>,
        activeShoutChannelId: Int?,
        supportsChannelListen: Boolean,
        mayWhisper: (Int) -> Boolean,
        canListen: (Int) -> Boolean,
        canTextMessage: (Int) -> Boolean,
        canAddChannel: (Int) -> Boolean,
        canMakePermanentChannel: (Int) -> Boolean,
        canWriteChannel: (Int) -> Boolean,
        canLinkChannel: (Int) -> Boolean,
    ): ChannelNodeFlags {
        val isCurrentChannel = channel.id == localChannelId
        val listening = channel.id in listeningChannels
        val shoutActive = activeShoutChannelId == channel.id
        val showAdd = !channel.temporary && canAddChannel(channel.id)
        val showEdit = canWriteChannel(channel.id)
        // Home's own channel cannot be linked to itself, and unlinking it would
        // contradict localChannelId.
        val isLinked = channel.id in homeAllLinks && homeAllLinks.size > 1
        return ChannelNodeFlags(
            // Joining the channel you are already in is a no-op.
            showJoin = !isCurrentChannel,
            showSend = canTextMessage(channel.id),
            // Shout requires Whisper on the channel; murmur re-checks it when
            // the target is registered.
            showShout = shoutActive || mayWhisper(channel.id),
            shoutActive = shoutActive,
            showListen = supportsChannelListen && (canListen(channel.id) || listening),
            listening = listening,
            // Temporary channels are already ephemeral; the server rejects a
            // permanent flag on them.
            showAdd = showAdd,
            showEdit = showEdit,
            // The root channel cannot be removed.
            showRemove = showEdit && channel.id != 0,
            forceTemporary = showAdd && !canMakePermanentChannel(channel.id),
            canCollapse = ChannelTree.canCollapse(channel),
            collapsed = ChannelTree.canCollapse(channel) && channel.id in collapsedIds,
            isCurrentChannel = isCurrentChannel,
            isLinked = isLinked,
            showLinkIcon = isLinked && !isCurrentChannel,
        )
    }

    /** Menu model for the link/unlink submenu of this node. */
    fun linkMenu(
        channel: Channel,
        localChannelId: Int,
        homeAllLinks: Set<Int>,
        homeDirectLinks: Set<Int>,
        canLinkChannel: (Int) -> Boolean,
    ): ChannelLinks.Menu = ChannelLinks.menu(
        homeId = localChannelId,
        targetId = channel.id,
        homeDirectLinks = homeDirectLinks,
        targetInHomeComponent = channel.id in homeAllLinks,
        homeCanLink = canLinkChannel(localChannelId),
        targetCanLink = canLinkChannel(channel.id),
    )
}
