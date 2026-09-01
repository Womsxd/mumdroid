package dev.woms.mumdroid.core.model

/**
 * After server sync the server may place an unregistered user (or a registered
 * user whose last channel was not stored) in the default channel. Resolve the
 * channel to rejoin so a reconnect stays in the room from before the drop.
 */
object LastChannelRestore {
    data class Target(val id: Int, val name: String = "")

    /**
     * Channel id to join, or `null` when the remembered room is gone or the
     * user is already there (stay where the server placed us).
     */
    fun resolve(remembered: Target?, currentChannelId: Int, channels: Collection<Channel>): Int? {
        if (remembered == null || channels.isEmpty()) return null
        val byId = channels.firstOrNull { it.id == remembered.id }
        if (byId != null) {
            return if (byId.id != currentChannelId) byId.id else null
        }
        if (remembered.name.isEmpty()) return null
        val byName = channels.filter { it.name == remembered.name }
        if (byName.size != 1) return null
        return if (byName[0].id != currentChannelId) byName[0].id else null
    }
}
