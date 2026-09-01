package dev.woms.mumdroid.service

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AccessTokens
import dev.woms.mumdroid.core.model.ChannelModeration
import dev.woms.mumdroid.core.net.MumbleClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Join / move / listen and channel create-update-link sends.
 */
internal class SessionChannels(
    private val scope: CoroutineScope,
    private val roster: SessionRoster,
    private val admin: ServerAdminSession,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun client(): MumbleClient?
        suspend fun persistAccessToken(channelId: Int, token: String)
        fun rememberChannel(channelId: Int)
        fun clearRestorePending()
        fun appendSystem(message: String)
        fun updateConnectedStatus(channelName: String)
        fun serverName(): String
        fun getString(id: Int): String
        fun getString(id: Int, vararg formatArgs: Any): String
    }

    fun join(channelId: Int, announceMove: Boolean = true, accessToken: String? = null) {
        val c = callbacks.client() ?: return
        val previous = roster.localUser()?.channelId
        callbacks.clearRestorePending()
        admin.clearPasswordPrompt()
        val token = accessToken?.let { AccessTokens.normalize(it) }.orEmpty()
        val usingPassword = token.isNotEmpty()
        admin.notePasswordJoin(if (usingPassword) channelId else null)
        if (!usingPassword) {
            callbacks.rememberChannel(channelId)
        }
        scope.launch {
            if (usingPassword) {
                callbacks.persistAccessToken(channelId, token)
                c.setTokens(admin.tokens())
                c.joinChannel(channelId, listOf(token))
            } else {
                c.joinChannel(channelId)
            }
            c.queryPermissions(channelId)
        }
        if (announceMove && !usingPassword && previous != null && previous != channelId) {
            val name = roster.localUser()?.name ?: callbacks.serverName()
            callbacks.appendSystem(
                callbacks.getString(
                    R.string.chat_user_moved,
                    name,
                    roster.channelName(previous),
                    roster.channelName(channelId),
                ),
            )
        }
        if (!usingPassword) {
            callbacks.updateConnectedStatus(roster.channelMap[channelId]?.name.orEmpty())
        }
    }

    fun moveUser(session: Int, channelId: Int) {
        if (session == roster.localSession) {
            join(channelId)
            return
        }
        val c = callbacks.client() ?: return
        scope.launch { c.moveUser(session, channelId) }
    }

    fun link(targetId: Int) {
        val home = roster.localUser()?.channelId ?: return
        if (home == targetId) return
        val c = callbacks.client() ?: return
        scope.launch { c.updateChannel(ChannelModeration.addLink(home, targetId)) }
    }

    fun unlink(targetId: Int) {
        val home = roster.localUser()?.channelId ?: return
        if (home == targetId) return
        val c = callbacks.client() ?: return
        scope.launch { c.updateChannel(ChannelModeration.removeLink(home, targetId)) }
    }

    fun unlinkAll() {
        val home = roster.localUser()?.channelId ?: return
        val links = roster.channelMap[home]?.linkedIds ?: return
        val msg = ChannelModeration.unlinkAll(home, links) ?: return
        val c = callbacks.client() ?: return
        scope.launch { c.updateChannel(msg) }
    }

    fun create(
        parentId: Int,
        name: String,
        description: String,
        position: Int,
        temporary: Boolean,
        maxUsers: Int,
        password: String = "",
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val c = callbacks.client() ?: return
        admin.queueCreatePassword(parentId, trimmed, password)
        scope.launch {
            c.createChannel(parentId, trimmed, description, position, temporary, maxUsers)
        }
    }

    fun update(
        channelId: Int,
        name: String,
        description: String,
        position: Int,
        maxUsers: Int,
        password: String = "",
        applyPassword: (Int, String) -> Unit,
    ) {
        val current = roster.channelMap[channelId] ?: return
        val c = callbacks.client() ?: return
        val msg = ChannelModeration.update(
            current,
            name = name.trim(),
            description = description,
            position = position,
            maxUsers = maxUsers,
        )
        scope.launch {
            if (msg != null) c.updateChannel(msg)
            applyPassword(channelId, password)
        }
    }

    fun remove(channelId: Int) {
        if (channelId == 0) return
        val c = callbacks.client() ?: return
        scope.launch { c.removeChannel(channelId) }
    }

    fun requestDescription(channelId: Int) {
        val c = callbacks.client() ?: return
        if (roster.channelMap[channelId]?.description?.isNotEmpty() == true) return
        scope.launch { c.requestChannelDescription(channelId) }
    }

    fun setListening(channelId: Int, listen: Boolean) {
        val c = callbacks.client() ?: return
        scope.launch { c.setChannelListening(channelId, listen) }
    }

    fun ensurePermissions(channelId: Int) {
        if (roster.hasPermissions(channelId)) return
        callbacks.client()?.queryPermissions(channelId)
    }
}
