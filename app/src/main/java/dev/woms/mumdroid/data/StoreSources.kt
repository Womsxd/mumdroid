package dev.woms.mumdroid.data

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Narrow views over the stores the session layer reads at connect time.
 *
 * The concrete stores are `Context`-backed, which made the connect assembly and
 * last-channel logic untestable off-device. Depending on these interfaces keeps
 * those collaborators replaceable with fakes.
 */

/** The client-certificate material a connection should present. */
internal interface CertificateMaterialSource {
    suspend fun keyStoreMaterial(): Pair<X509Certificate, PrivateKey>?
}

/** Resolves a saved-server id (keeping or creating one) for a host/port pair. */
internal interface ServerIdSource {
    suspend fun resolveId(serverId: Long, host: String, port: Int): Long
}

/** Stored access tokens for a server. */
internal interface AccessTokenSource {
    suspend fun tokensFor(host: String, port: Int): List<String>
}

/** The pinned server certificate fingerprint, when one was saved. */
internal interface PinnedFingerprintSource {
    suspend fun pinnedFingerprint(host: String, port: Int): String?
}

/** Last-channel persistence used to restore a user's channel after a reconnect. */
internal interface LastChannelStore : ServerIdSource {
    suspend fun getLastChannel(serverId: Long): ServerStore.LastChannel?
    suspend fun setLastChannel(serverId: Long, channelId: Int, channelName: String)
}
