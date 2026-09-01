package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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
