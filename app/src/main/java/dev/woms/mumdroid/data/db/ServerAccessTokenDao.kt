package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ServerAccessTokenDao {

    @Query("SELECT token FROM server_access_tokens WHERE host = :host AND port = :port")
    suspend fun tokensForAddress(host: String, port: Int): List<String>

    @Query("DELETE FROM server_access_tokens WHERE host = :host AND port = :port")
    suspend fun deleteForAddress(host: String, port: Int)

    @Insert
    suspend fun insert(entity: ServerAccessTokenEntity): Long

    @Transaction
    suspend fun replaceForAddress(host: String, port: Int, tokens: List<String>) {
        deleteForAddress(host, port)
        for (token in tokens) {
            insert(ServerAccessTokenEntity(host = host, port = port, token = token))
        }
    }
}
