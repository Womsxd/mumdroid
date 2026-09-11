package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * A channel password (official access token) remembered for one server
 * address and one channel. Distinct from the server login password on
 * [ServerEntity]. Shared across favorites of the same host:port.
 */
@Entity(
    tableName = "channel_access_tokens",
    indices = [
        Index(value = ["host", "port", "channel_id"], unique = true),
        Index(value = ["host", "port"]),
    ],
)
data class ChannelAccessTokenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "channel_id")
    val channelId: Int,
    val token: String,
)

/** Row access for [ChannelAccessTokenEntity]. */
@Dao
interface ChannelAccessTokenDao {

    @Query(
        "SELECT * FROM channel_access_tokens WHERE host = :host AND port = :port AND channel_id = :channelId LIMIT 1",
    )
    suspend fun find(host: String, port: Int, channelId: Int): ChannelAccessTokenEntity?

    @Insert
    suspend fun insert(entity: ChannelAccessTokenEntity): Long

    @Update
    suspend fun update(entity: ChannelAccessTokenEntity)
}
