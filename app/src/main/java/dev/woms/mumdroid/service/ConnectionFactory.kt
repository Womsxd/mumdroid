package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.MumbleListener
import dev.woms.mumdroid.data.AccessTokenSource
import dev.woms.mumdroid.data.CertificateMaterialSource
import dev.woms.mumdroid.data.PinnedFingerprintSource
import dev.woms.mumdroid.data.ServerIdSource
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Client certificate, access tokens and pin assembled for one connect. */
internal data class PreparedConnection(
    val client: MumbleClient,
    val resolvedServerId: Long,
    val accessTokens: List<String>,
)

/** Everything [MumbleClient] needs for one connect, resolved by [ConnectionFactory]. */
internal data class ConnectionClientSpec(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val listener: MumbleListener,
    val clientCert: X509Certificate?,
    val clientKey: PrivateKey?,
    val accessTokens: List<String>,
    val certificatePinning: Boolean,
    val pinnedFingerprint: String?,
    val hideClientInfo: Boolean,
    val allowLegacyTls: Boolean,
)

/** Builds the client for a resolved [ConnectionClientSpec]. */
internal fun interface ClientBuilder {
    fun build(spec: ConnectionClientSpec): MumbleClient
}

/**
 * Loads TLS credentials and stored tokens, then builds [MumbleClient].
 * [MumbleService] keeps connect/disconnect lifecycle only.
 */
internal class ConnectionFactory(
    private val certificateMaterial: CertificateMaterialSource,
    private val serverIds: ServerIdSource,
    private val tokenSource: AccessTokenSource,
    private val pins: PinnedFingerprintSource,
    private val buildClient: ClientBuilder = ClientBuilder(::defaultClient),
) {
    suspend fun create(
        params: ConnectParams,
        certificatePinning: Boolean,
        hideClientInfo: Boolean,
        allowLegacyTls: Boolean,
        listener: MumbleListener,
    ): PreparedConnection {
        val (clientCert, clientKey) = certificateMaterial.keyStoreMaterial()
            ?: (null to null)
        val resolvedServerId = serverIds.resolveId(params.serverId, params.host, params.port)
        val accessTokens = tokenSource.tokensFor(params.host, params.port)
        val pinnedFingerprint = if (certificatePinning) {
            pins.pinnedFingerprint(params.host, params.port)
        } else {
            null
        }
        val client = buildClient.build(
            ConnectionClientSpec(
                host = params.host,
                port = params.port,
                username = params.username,
                password = params.password,
                listener = listener,
                clientCert = clientCert,
                clientKey = clientKey,
                accessTokens = accessTokens,
                certificatePinning = certificatePinning,
                pinnedFingerprint = pinnedFingerprint,
                hideClientInfo = hideClientInfo,
                allowLegacyTls = allowLegacyTls,
            ),
        )
        return PreparedConnection(client, resolvedServerId, accessTokens)
    }
}

private fun defaultClient(spec: ConnectionClientSpec): MumbleClient = MumbleClient(
    spec.host,
    spec.port,
    spec.username,
    spec.password,
    spec.listener,
    clientCert = spec.clientCert,
    clientKey = spec.clientKey,
    initialAccessTokens = spec.accessTokens,
    certificatePinning = spec.certificatePinning,
    pinnedFingerprint = spec.pinnedFingerprint,
    hideClientInfo = spec.hideClientInfo,
    allowLegacyTls = spec.allowLegacyTls,
)
