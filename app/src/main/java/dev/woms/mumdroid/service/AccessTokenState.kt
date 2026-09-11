package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.AccessTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Where the token bag is stored. Implemented by an adapter over
 * [dev.woms.mumdroid.data.ChannelAccessTokenStore], so this state machine can be
 * tested without a database.
 */
internal interface AccessTokenPersistence {
    /** Writes the per-server list and returns what the store actually kept. */
    suspend fun replaceServerTokens(tokens: List<String>): List<String>

    /** Records one channel password in the per-channel mapping. */
    suspend fun upsertChannelToken(channelId: Int, token: String)
}

/**
 * The session's access-token bag (desktop `ServerHandler::setTokens`).
 *
 * Two persisted shapes exist and must stay in sync: the per-channel mapping the
 * join prompt reads, and the per-server-address list sent in `Authenticate`.
 * This class owns the ordering so the UI list and the wire list never disagree:
 * the per-server list is persisted first and the store's answer (it sanitizes
 * and de-duplicates on its own) becomes the value pushed to the client.
 */
internal class AccessTokenState(private val scope: CoroutineScope) {

    private val _accessTokens = MutableStateFlow<List<String>>(emptyList())
    val accessTokens: StateFlow<List<String>> = _accessTokens

    fun setTokens(tokens: List<String>) {
        _accessTokens.value = tokens
    }

    fun tokens(): List<String> = _accessTokens.value

    fun clearTokens() {
        _accessTokens.value = emptyList()
    }

    /** Sanitizes, persists and pushes the new list to the live client. */
    fun replace(
        tokens: List<String>,
        persistence: AccessTokenPersistence,
        pushToClient: (List<String>) -> Unit,
    ) {
        val sanitized = AccessTokens.sanitize(tokens)
        setTokens(sanitized)
        scope.launch {
            val stored = persistence.replaceServerTokens(sanitized)
            setTokens(stored)
            pushToClient(stored)
        }
    }

    /**
     * Adds one channel password: it goes into the per-channel mapping *and*
     * into the server list, which is what the next `Authenticate` carries.
     */
    suspend fun persistChannelToken(
        channelId: Int,
        token: String,
        persistence: AccessTokenPersistence,
    ) {
        val normalized = AccessTokens.normalize(token)
        val next = AccessTokens.sanitize(AccessTokens.add(tokens(), normalized))
        setTokens(next)
        persistence.upsertChannelToken(channelId, normalized)
        setTokens(persistence.replaceServerTokens(next))
    }
}
