package dev.woms.mumdroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
