package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.ClientTlsPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set put on the socket before the handshake: a TLS 1.2 floor by default,
 * widened to TLS 1.0/1.1 only when the user allows legacy TLS — and always
 * bounded by what the platform still supports.
 */
class TlsProtocolFloorTest {

    /** Android 10+ (API 29+): the platform supports TLS 1.0 through 1.3. */
    @Test
    fun dropsTls10And11_keeps13WhereSupported() {
        assertArrayEquals(
            arrayOf("TLSv1.2", "TLSv1.3"),
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"),
                allowLegacyTls = false,
            ),
        )
    }

    /** API 26-28: TLS 1.3 is absent, and 1.0/1.1 must not survive the floor. */
    @Test
    fun keepsOnlyTls12_whenPlatformHasNoTls13() {
        assertArrayEquals(
            arrayOf("TLSv1.2"),
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1", "TLSv1.1", "TLSv1.2"),
                allowLegacyTls = false,
            ),
        )
    }

    /**
     * The platform's order is kept: Conscrypt turns the enabled set into a
     * min/max range, so the caller's order is preserved rather than rewritten.
     */
    @Test
    fun keepsPlatformOrder() {
        assertArrayEquals(
            arrayOf("TLSv1.2", "TLSv1.3"),
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1.2", "TLSv1.3"),
                allowLegacyTls = false,
            ),
        )
    }

    /** Legacy TLS allowed: the old versions join the set. */
    @Test
    fun allowLegacyTls_addsTls10And11() {
        assertArrayEquals(
            arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"),
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"),
                allowLegacyTls = true,
            ),
        )
    }

    /**
     * Android 15+ drops TLS 1.0/1.1 from `supportedProtocols` for apps
     * targeting 35+. The setting must then be a no-op rather than an
     * `IllegalArgumentException` from `setEnabledProtocols`.
     */
    @Test
    fun allowLegacyTls_degradesToTheFloorWherePlatformRefusesThem() {
        assertArrayEquals(
            arrayOf("TLSv1.2", "TLSv1.3"),
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1.2", "TLSv1.3"),
                allowLegacyTls = true,
            ),
        )
    }

    /**
     * A platform with neither TLS 1.2 nor 1.3 cannot satisfy the floor at all.
     * Handing `setEnabledProtocols` the empty intersection would make Conscrypt
     * fail the handshake with "No enabled protocols" — a message that points at
     * its internals, not at the floor this app chose. The policy must refuse
     * first, naming itself. Unreachable on a device (minSdk 26 always offers
     * TLS 1.2); this pins the diagnostic, not a real-world path.
     */
    @Test
    fun refusesAnEmptySetInsteadOfLettingConscryptFailLater() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ClientTlsPolicy.enabledProtocolsFor(
                arrayOf("TLSv1", "TLSv1.1", "SSLv3"),
                allowLegacyTls = false,
            )
        }
        assertTrue(e.message!!.contains("no acceptable TLS version"))
        assertTrue("should name what the platform offered: ${e.message}", e.message!!.contains("SSLv3"))
    }
}
