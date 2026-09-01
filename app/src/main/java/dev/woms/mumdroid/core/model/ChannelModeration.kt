package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.ChannelRemove
import dev.woms.mumdroid.core.proto.ChannelState

/**
 * Builds channel create / update / remove payloads the way the desktop
 * client does (`ServerHandler::createChannel` / `removeChannel`,
 * `ACLEditor::accept`).
 */
object ChannelModeration {
    fun create(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
    ): ChannelState =
        ChannelState.newBuilder()
            .setParent(parentId)
            .setName(name)
            .setDescription(description)
            .setPosition(position)
            .setTemporary(temporary)
            .setMaxUsers(maxUsers)
            .build()

    /**
     * Desktop only sends fields that changed. Null means nothing to send.
     * Root (`channelId == 0`) never includes a name change.
     */
    fun update(
        current: Channel,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
    ): ChannelState? {
        val builder = ChannelState.newBuilder().setChannelId(current.id)
        var changed = false
        if (current.id != 0 && name != current.name) {
            builder.setName(name)
            changed = true
        }
        if (description != current.description) {
            builder.setDescription(description)
            changed = true
        }
        if (position != current.position) {
            builder.setPosition(position)
            changed = true
        }
        if (maxUsers != current.maxUsers) {
            builder.setMaxUsers(maxUsers)
            changed = true
        }
        return if (changed) builder.build() else null
    }

    /** Desktop `ServerHandler::addChannelLink`. */
    fun addLink(channelId: Int, linkId: Int): ChannelState =
        ChannelState.newBuilder().setChannelId(channelId).addLinksAdd(linkId).build()

    /** Desktop `ServerHandler::removeChannelLink`. */
    fun removeLink(channelId: Int, linkId: Int): ChannelState =
        ChannelState.newBuilder().setChannelId(channelId).addLinksRemove(linkId).build()

    /**
     * Desktop `on_qaChannelUnlinkAll_triggered`: `links_remove` for every
     * direct partner of the current channel.
     */
    fun unlinkAll(channelId: Int, linkedIds: Collection<Int>): ChannelState? {
        if (linkedIds.isEmpty()) return null
        val builder = ChannelState.newBuilder().setChannelId(channelId)
        linkedIds.forEach { builder.addLinksRemove(it) }
        return builder.build()
    }

    fun remove(channelId: Int): ChannelRemove =
        ChannelRemove.newBuilder().setChannelId(channelId).build()
}
