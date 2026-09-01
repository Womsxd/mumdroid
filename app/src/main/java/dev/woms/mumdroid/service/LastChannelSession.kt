package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.LastChannelRestore
import dev.woms.mumdroid.data.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Remembers the local user's channel across reconnects and restores it
 * after server sync.
 */
internal class LastChannelSession(
    private val scope: CoroutineScope,
    private val roster: SessionRoster,
) {
    private lateinit var serverStore: ServerStore

    var restorePending = false
        private set

    private var remembered: Remembered? = null

    data class Remembered(val serverKey: String, val id: Int, val name: String)

    fun attach(store: ServerStore) {
        serverStore = store
    }

    fun clearPending() {
        restorePending = false
    }

    suspend fun prepareForConnect(host: String, port: Int, serverId: Long) {
        val key = serverKey(host, port)
        if (remembered?.serverKey != key) {
            val sid = serverStore.resolveId(serverId, host, port)
            remembered = serverStore.getLastChannel(sid)?.let {
                Remembered(key, it.id, it.name)
            }
        }
        restorePending = remembered?.serverKey == key
    }

    fun remember(channelId: Int, host: String, port: Int, serverIdHint: Long) {
        val key = serverKey(host, port).takeIf { host.isNotEmpty() } ?: return
        val name = roster.channelMap[channelId]?.name ?: ""
        val already = remembered
        if (already?.serverKey == key && already.id == channelId && already.name == name) return
        remembered = Remembered(key, channelId, name)
        scope.launch {
            val sid = serverStore.resolveId(serverIdHint, host, port)
            serverStore.setLastChannel(sid, channelId, name)
        }
    }

    fun persistFromLocal(host: String, port: Int, serverIdHint: Long) {
        val user = roster.localUser() ?: return
        remember(user.channelId, host, port, serverIdHint)
    }

    fun restoreAfterSync(
        host: String,
        port: Int,
        joinWithoutAnnounce: (channelId: Int) -> Unit,
        onStay: () -> Unit,
        announceJoin: (channelId: Int) -> Unit,
    ) {
        val key = if (host.isNotEmpty()) serverKey(host, port) else null
        val stored = remembered?.takeIf { it.serverKey == key }
        val current = roster.localUser()?.channelId ?: 0
        val target = LastChannelRestore.resolve(
            stored?.let { LastChannelRestore.Target(it.id, it.name) },
            current,
            roster.channelMap.values,
        )
        val deferredHint = restorePending && stored != null
        restorePending = false
        if (target != null) {
            joinWithoutAnnounce(target)
        } else {
            onStay()
        }
        if (deferredHint) {
            val landed = target ?: current
            if (landed != 0) announceJoin(landed)
        }
    }

    private fun serverKey(host: String, port: Int) = "$host:$port"
}
