package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One access token for a server address, matching desktop `tokens` rows
 * (`digest` + `token`). Shared by every favorite of that host:port — not
 * isolated by local username, because a registered client certificate still
 * authenticates as the same server-side user.
 */
@Entity(
    tableName = "server_access_tokens",
    indices = [Index(value = ["host", "port"])],
)
data class ServerAccessTokenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val host: String,
    val port: Int,
    val token: String,
)

/** Row access for [ServerAccessTokenEntity]. */
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
