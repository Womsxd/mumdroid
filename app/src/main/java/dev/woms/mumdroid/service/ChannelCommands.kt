package dev.woms.mumdroid.service

/**
 * Channel join / move / link / create-update-remove, listen-in and the lazy
 * permission query for a channel.
 */
internal class ChannelCommands(
    private val sessionChannels: SessionChannels,
    private val applyPassword: (channelId: Int, password: String) -> Unit,
) {
    fun joinChannel(channelId: Int, accessToken: String? = null) =
        sessionChannels.join(channelId, accessToken = accessToken)

    fun moveUser(session: Int, channelId: Int) = sessionChannels.moveUser(session, channelId)

    fun linkChannel(targetId: Int) = sessionChannels.link(targetId)
    fun unlinkChannel(targetId: Int) = sessionChannels.unlink(targetId)
    fun unlinkAllChannels() = sessionChannels.unlinkAll()

    fun createChannel(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String = "",
    ) = sessionChannels.create(parentId, name, description, position, temporary, maxUsers, password)

    fun updateChannel(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String = "",
    ) = sessionChannels.update(channelId, name, description, position, maxUsers, password, applyPassword)

    fun removeChannel(channelId: Int) = sessionChannels.remove(channelId)
    fun requestChannelDescription(channelId: Int) = sessionChannels.requestDescription(channelId)
    fun setChannelListening(channelId: Int, listen: Boolean) = sessionChannels.setListening(channelId, listen)
    fun ensureChannelPermissions(channelId: Int) = sessionChannels.ensurePermissions(channelId)
}
