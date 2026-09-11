package dev.woms.mumdroid.data

import android.content.Context
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.data.db.MumdroidDatabase
import dev.woms.mumdroid.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for the saved Mumble server list, backed by Room.
 *
 * Replaces the previous DataStore+JSON storage with a typed SQLite table so the
 * list can be queried, sorted and scaled cleanly. The insert-vs-update decision
 * that keeps a favorite's Room identity ([ServerSave]) lives at the bottom of
 * this file: it is the store's own save rule.
 */
class ServerStore(private val context: Context) {

    private val dao = MumdroidDatabase.getInstance(context).serverDao()

    data class LastChannel(val id: Int, val name: String = "")

    /** Emits the current list of saved servers (most recently used first). */
    val servers: Flow<List<MumbleServer>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /** The last server the user connected to (for reconnection). */
    val lastConnected: Flow<MumbleServer?> = dao.observeAll().map { list -> list.firstOrNull()?.toModel() }

    /**
     * Adds a new favorite. Same host:port as an existing card is allowed
     * (desktop has no unique on hostname+port).
     */
    suspend fun addServer(server: MumbleServer) {
        saveFavorite(server, editingId = 0L)
    }

    /**
     * Saves a favorite in place so last-channel memory stays on the same
     * `servers.id`. Pass [editingId] when the user is editing an existing card.
     */
    suspend fun saveFavorite(server: MumbleServer, editingId: Long = server.id) {
        val existing = editingId.takeIf { it > 0L }?.let { dao.getById(it) }
        val plan = ServerSave.plan(existing?.id ?: 0L)
        if (plan.updateId > 0L) {
            applyFields(plan.updateId, server)
        } else {
            dao.insert(server.toEntity())
        }
    }

    suspend fun updateServer(server: MumbleServer) {
        saveFavorite(server, editingId = server.id)
    }

    private suspend fun applyFields(id: Long, server: MumbleServer) {
        val row = dao.getById(id) ?: return
        dao.update(
            server.toEntity(
                lastConnectedAt = row.lastConnectedAt,
                lastChannelId = row.lastChannelId,
                lastChannelName = row.lastChannelName,
            ).copy(
                id = row.id,
                certificateAlias = server.certificateAlias ?: row.certificateAlias,
            ),
        )
    }

    suspend fun removeServer(server: MumbleServer) {
        val id = server.id.takeIf { it > 0L }
        if (id != null) {
            dao.deleteById(id)
            return
        }
        dao.findByHostPort(server.host, server.port)?.let { dao.delete(it) }
    }

    suspend fun resolveId(serverId: Long, host: String, port: Int): Long {
        if (serverId > 0L && dao.getById(serverId) != null) return serverId
        return dao.findByHostPort(host, port)?.id ?: 0L
    }

    suspend fun markConnected(server: MumbleServer) {
        val now = System.currentTimeMillis()
        val existing = server.id.takeIf { it != 0L }?.let { dao.getById(it) }
            ?: if (server.id == 0L) dao.findByHostPort(server.host, server.port) else null
        val id = existing?.id ?: dao.insert(server.toEntity(now))
        dao.touchLastConnected(id, now)
    }

    suspend fun getLastChannel(serverId: Long): LastChannel? {
        if (serverId <= 0L) return null
        val row = dao.getById(serverId) ?: return null
        val id = row.lastChannelId ?: return null
        return LastChannel(id, row.lastChannelName)
    }

    suspend fun setLastChannel(serverId: Long, channelId: Int, channelName: String) {
        if (serverId <= 0L) return
        dao.setLastChannel(serverId, channelId, channelName)
    }

    /** Copies leftover DataStore last-channel rows onto matching server rows. */
    suspend fun importLegacyLastChannels(entries: List<SettingsStore.LegacyLastChannel>) {
        for (entry in entries) {
            val server = dao.findByHostPort(entry.host, entry.port) ?: continue
            if (server.lastChannelId != null) continue
            dao.setLastChannel(server.id, entry.id, entry.name)
        }
    }
}

/**
 * How to persist a favorite without dropping its Room identity.
 *
 * Desktop `servers` has no unique on hostname+port, so two cards may share an
 * address and differ only by local name/username. Editing updates that row;
 * adding always inserts. Access tokens are stored by address, not by this id.
 */
object ServerSave {
    data class Plan(
        /** Existing row to UPDATE; 0 means INSERT a new favorite. */
        val updateId: Long = 0,
    ) {
        val isInsert: Boolean get() = updateId <= 0L
    }

    fun plan(editingId: Long): Plan {
        return if (editingId > 0L) Plan(updateId = editingId) else Plan()
    }
}
