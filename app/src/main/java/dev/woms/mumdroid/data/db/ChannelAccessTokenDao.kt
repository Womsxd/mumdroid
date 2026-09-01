package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

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
