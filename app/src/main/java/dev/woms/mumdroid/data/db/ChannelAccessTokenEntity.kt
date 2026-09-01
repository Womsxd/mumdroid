package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
