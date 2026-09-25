package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChannelUpdate
import dev.woms.mumdroid.core.model.UserUpdate
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.MumbleListener
import dev.woms.mumdroid.data.AccessTokenSource
import dev.woms.mumdroid.data.CertificateMaterialSource
import dev.woms.mumdroid.data.PinnedFingerprintSource
import dev.woms.mumdroid.data.ServerIdSource
import dev.woms.mumdroid.service.ClientBuilder
import dev.woms.mumdroid.service.ConnectParams
import dev.woms.mumdroid.service.ConnectionClientSpec
import dev.woms.mumdroid.service.ConnectionFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Connect-time assembly: which certificate, tokens, server id and pin are
 * resolved, and how they are handed to the client builder.
 */
class ConnectionFactoryTest {

    private class NoopListener : MumbleListener {
        override fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int) {}
        override fun onRejected(reason: String, type: Int) {}
        override fun onDisconnected(reason: String) {}
        override fun onChannelState(update: ChannelUpdate) {}
        override fun onChannelRemoved(channelId: Int) {}
        override fun onUserState(update: UserUpdate) {}
        override fun onUserRemoved(
            session: Int,
            actor: Int,
            hasActor: Boolean,
            reason: String,
            ban: Boolean,
        ) {}

        override fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean) {}
        override fun onInfo(message: String) {}
        override fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {}
    }

    private class FakeMaterial : CertificateMaterialSource {
        var material: Pair<X509Certificate, PrivateKey>? = null
        override suspend fun keyStoreMaterial(): Pair<X509Certificate, PrivateKey>? = material
    }

    private class FakeServerIds : ServerIdSource {
        var id: Long = 0
        var calls = 0
        override suspend fun resolveId(serverId: Long, host: String, port: Int): Long {
            calls++
            return id
        }
    }

    private class FakeTokens : AccessTokenSource {
        var tokens: List<String> = emptyList()
        override suspend fun tokensFor(host: String, port: Int): List<String> = tokens
    }

    private class FakePins : PinnedFingerprintSource {
        var fingerprint: String? = null
        var calls = 0
        override suspend fun pinnedFingerprint(host: String, port: Int): String? {
            calls++
            return fingerprint
        }
    }

    private val material = FakeMaterial()
    private val serverIds = FakeServerIds()
    private val tokens = FakeTokens()
    private val pins = FakePins()
    private var captured: ConnectionClientSpec? = null

    private fun factory() = ConnectionFactory(
        certificateMaterial = material,
        serverIds = serverIds,
        tokenSource = tokens,
        pins = pins,
        buildClient = ClientBuilder { spec ->
            captured = spec
            MumbleClient(spec.host, spec.port, spec.username, spec.password, NoopListener())
        },
    )

    private fun params() = ConnectParams(
        host = "example.org",
        port = 64738,
        username = "user",
        password = "pass",
        displayName = "User",
    )

    @Test
    fun create_resolvesIdTokensAndPin() {
        serverIds.id = 42
        tokens.tokens = listOf("a", "b")
        pins.fingerprint = "FP"
        val listener = NoopListener()

        val prepared = runBlocking {
            factory().create(
                params(),
                certificatePinning = true,
                hideClientInfo = false,
                allowLegacyTls = false,
                listener = listener,
            )
        }

        assertEquals(42L, prepared.resolvedServerId)
        assertEquals(listOf("a", "b"), prepared.accessTokens)
        val spec = requireNotNull(captured)
        assertEquals("example.org", spec.host)
        assertEquals(64738, spec.port)
        assertEquals("user", spec.username)
        assertEquals("pass", spec.password)
        assertSame(listener, spec.listener)
        assertNull("no stored certificate", spec.clientCert)
        assertNull(spec.clientKey)
        assertEquals(listOf("a", "b"), spec.accessTokens)
        assertTrue(spec.certificatePinning)
        assertEquals("FP", spec.pinnedFingerprint)
    }

    @Test
    fun create_skipsThePinLookupWhenPinningIsDisabled() {
        pins.fingerprint = "FP"

        val prepared = runBlocking {
            factory().create(
                params(),
                certificatePinning = false,
                hideClientInfo = false,
                allowLegacyTls = false,
                listener = NoopListener(),
            )
        }

        assertEquals("pinning off must not read the store", 0, pins.calls)
        assertNull(requireNotNull(captured).pinnedFingerprint)
        assertEquals(0L, prepared.resolvedServerId)
    }

    @Test
    fun create_carriesTheOsInfoPreferenceIntoTheSpec() {
        // The handshake reads it from the client, so it has to survive the trip
        // from the connect call to the spec.
        runBlocking {
            factory().create(
                params(),
                certificatePinning = true,
                hideClientInfo = true,
                allowLegacyTls = false,
                listener = NoopListener(),
            )
        }

        assertTrue(requireNotNull(captured).hideClientInfo)
    }

    @Test
    fun create_carriesTheLegacyTlsPreferenceIntoTheSpec() {
        // Same trip as the OS-info flag: the TLS policy reads it from the
        // client at handshake time.
        runBlocking {
            factory().create(
                params(),
                certificatePinning = true,
                hideClientInfo = false,
                allowLegacyTls = true,
                listener = NoopListener(),
            )
        }

        assertTrue(requireNotNull(captured).allowLegacyTls)
    }
}
