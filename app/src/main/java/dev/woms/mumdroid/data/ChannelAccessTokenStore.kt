package dev.woms.mumdroid.data

import android.content.Context
import dev.woms.mumdroid.core.model.AccessTokens
import dev.woms.mumdroid.core.model.ServerAddress
import dev.woms.mumdroid.data.db.ChannelAccessTokenEntity
import dev.woms.mumdroid.data.db.MumdroidDatabase

/**
 * Channel passwords stay mapped to a channel for the join prompt.
 * The Authenticate token bag is per server address, matching desktop
 * `Database::getTokens` / `setTokens` (keyed by server identity, not
 * username or favorite row).
 */
class ChannelAccessTokenStore(context: Context) {

    private val db = MumdroidDatabase.getInstance(context)
    private val dao = db.channelAccessTokenDao()
    private val serverTokenDao = db.serverAccessTokenDao()

    suspend fun tokensFor(host: String, port: Int): List<String> {
        val key = ServerAddress.normalizeHost(host)
        if (key.isEmpty() || port <= 0) return emptyList()
        return AccessTokens.sanitize(serverTokenDao.tokensForAddress(key, port))
    }

    suspend fun replaceTokens(host: String, port: Int, tokens: List<String>): List<String> {
        val sanitized = AccessTokens.sanitize(tokens)
        val key = ServerAddress.normalizeHost(host)
        if (key.isEmpty() || port <= 0) return sanitized
        serverTokenDao.replaceForAddress(key, port, sanitized)
        return sanitized
    }

    suspend fun upsert(host: String, port: Int, channelId: Int, token: String) {
        val normalized = AccessTokens.normalize(token)
        val key = ServerAddress.normalizeHost(host)
        if (key.isEmpty() || port <= 0 || normalized.isEmpty()) return
        val existing = dao.find(key, port, channelId)
        if (existing != null) {
            if (existing.token != normalized) {
                dao.update(existing.copy(token = normalized))
            }
        } else {
            dao.insert(
                ChannelAccessTokenEntity(
                    host = key,
                    port = port,
                    channelId = channelId,
                    token = normalized,
                ),
            )
        }
    }
}
