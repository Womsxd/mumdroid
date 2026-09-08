package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.MumbleListener
import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.ChannelAccessTokenStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.data.UserCertificateStore

/** Client certificate, access tokens and pin assembled for one connect. */
internal data class PreparedConnection(
    val client: MumbleClient,
    val resolvedServerId: Long,
    val accessTokens: List<String>,
)

/**
 * Loads TLS credentials and stored tokens, then builds [MumbleClient].
 * [MumbleService] keeps connect/disconnect lifecycle only.
 */
internal class ConnectionFactory(
    private val userCertificateStore: UserCertificateStore,
    private val serverStore: ServerStore,
    private val channelAccessTokenStore: ChannelAccessTokenStore,
    private val certificateStore: CertificateStore,
) {
    suspend fun create(
        params: ConnectParams,
        certificatePinning: Boolean,
        listener: MumbleListener,
    ): PreparedConnection {
        val (clientCert, clientKey) = userCertificateStore.keyStoreMaterial()
            ?: (null to null)
        val resolvedServerId = serverStore.resolveId(params.serverId, params.host, params.port)
        val accessTokens = channelAccessTokenStore.tokensFor(params.host, params.port)
        val pinnedFingerprint = if (certificatePinning) {
            certificateStore.pinnedFingerprint(params.host, params.port)
        } else {
            null
        }
        val client = MumbleClient(
            params.host,
            params.port,
            params.username,
            params.password,
            listener,
            clientCert = clientCert,
            clientKey = clientKey,
            initialAccessTokens = accessTokens,
            certificatePinning = certificatePinning,
            pinnedFingerprint = pinnedFingerprint,
        )
        return PreparedConnection(client, resolvedServerId, accessTokens)
    }
}
