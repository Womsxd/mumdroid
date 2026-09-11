package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import dev.woms.mumdroid.core.model.MumbleServer
import kotlinx.coroutines.flow.Flow

/**
 * A saved Mumble server, persisted with Room.
 *
 * @property id auto-generated primary key.
 * @property name display name of the server.
 * @property host hostname / IP. Same host:port may appear on more than one
 *   row (desktop favorites are not unique on address).
 * @property port the Mumble port (default 64738).
 * @property username pre-filled username for the connection.
 * @property password stored password (kept plain for now, mirroring the
 *   previous DataStore behaviour).
 * @property certificateAlias the alias of the pinned certificate for this
 *   server, if any (references [CertificateEntity.alias]).
 * @property lastConnectedAt epoch millis of the last successful connect, used
 *   to sort the list (most recent first).
 * @property lastChannelId last Mumble channel the local user occupied here.
 * @property lastChannelName name of [lastChannelId], used when the id is gone.
 */
@Entity(
    tableName = "servers",
    indices = [Index(value = ["host", "port"])],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val host: String = "",
    val port: Int = 64738,
    val username: String = "",
    val password: String = "",
    @ColumnInfo(name = "certificate_alias")
    val certificateAlias: String? = null,
    @ColumnInfo(name = "last_connected_at")
    val lastConnectedAt: Long = 0,
    /** Last Mumble channel the local user occupied on this server. */
    @ColumnInfo(name = "last_channel_id")
    val lastChannelId: Int? = null,
    @ColumnInfo(name = "last_channel_name")
    val lastChannelName: String = "",
) {
    /** Converts to the UI model used throughout the app. */
    fun toModel(): MumbleServer = MumbleServer(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        password = password,
        certificateAlias = certificateAlias,
    )
}

/** Maps a [MumbleServer] into a [ServerEntity], preserving its id when present. */
fun MumbleServer.toEntity(
    lastConnectedAt: Long = 0,
    lastChannelId: Int? = null,
    lastChannelName: String = "",
): ServerEntity = ServerEntity(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    password = password,
    certificateAlias = certificateAlias,
    lastConnectedAt = lastConnectedAt,
    lastChannelId = lastChannelId,
    lastChannelName = lastChannelName,
)

/**
 * DAO for the saved-server list.
 */
@Dao
interface ServerDao {

    /** Emits all servers ordered by last-connected time (most recent first). */
    @Query("SELECT * FROM servers ORDER BY last_connected_at DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByHostPort(host: String, port: Int): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE servers SET last_connected_at = :timestamp WHERE id = :id")
    suspend fun touchLastConnected(id: Long, timestamp: Long)

    @Query(
        "UPDATE servers SET last_channel_id = :channelId, last_channel_name = :channelName WHERE id = :id",
    )
    suspend fun setLastChannel(id: Long, channelId: Int, channelName: String)
}
