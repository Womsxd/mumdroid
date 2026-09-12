package dev.woms.mumdroid

import dev.woms.mumdroid.data.UserCertificateCodec
import dev.woms.mumdroid.data.UserCertificatePkcs12
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.UnrecoverableKeyException
import java.util.Date
import javax.crypto.BadPaddingException

/**
 * The user-certificate store keeps its rules in small pieces around a thin
 * storage facade. Those pieces — subject/file naming, PKCS#12 failure
 * classification and the self-signed/pack/open round trip — are exercised here
 * without touching Android storage or Room.
 */
class UserCertificateStoreLogicTest {

    // ---- UserCertificateCodec.subjectFor ----

    @Test
    fun subjectFor_trimsAndPrefixes() {
        assertEquals("CN=Alice", UserCertificateCodec.subjectFor("  Alice "))
    }

    @Test
    fun subjectFor_blankFallsBackToDefault() {
        assertEquals("CN=mumdroid-user", UserCertificateCodec.subjectFor("   "))
    }

    @Test
    fun subjectFor_keepsExistingCnPrefix() {
        assertEquals("CN=Alice", UserCertificateCodec.subjectFor("CN=Alice"))
        assertEquals("CN=Alice", UserCertificateCodec.subjectFor("CN=  Alice  "))
    }

    @Test
    fun subjectFor_sanitisesCommasAndNewlines() {
        assertEquals("CN=Al_ice", UserCertificateCodec.subjectFor("Al,ice"))
        assertEquals("CN=Al_ice", UserCertificateCodec.subjectFor("Al\nice"))
        assertEquals("CN=Al_ice", UserCertificateCodec.subjectFor("Al\rice"))
    }

    // ---- file naming ----

    @Test
    fun certFileName_stripsFingerprintColons() {
        assertEquals(
            "user_cert_AA11BB22.p12",
            UserCertificateCodec.certFileName("AA:11:BB:22"),
        )
    }

    // ---- PKCS#12 failure classification ----

    @Test
    fun wrongPassword_detectsDedicatedExceptionTypes() {
        assertTrue(UserCertificateCodec.isWrongPasswordFailure(UnrecoverableKeyException("x")))
        assertTrue(UserCertificateCodec.isWrongPasswordFailure(BadPaddingException("x")))
    }

    @Test
    fun wrongPassword_detectsProviderMessages() {
        assertTrue(UserCertificateCodec.isWrongPasswordFailure(IOException("MAC verification failed")))
        assertTrue(UserCertificateCodec.isWrongPasswordFailure(Exception("password was incorrect")))
    }

    @Test
    fun wrongPassword_walksCauseChain() {
        val nested = Exception("wrapper", IOException("MAC verification failed"))
        assertTrue(UserCertificateCodec.isWrongPasswordFailure(nested))
    }

    @Test
    fun wrongPassword_falseForCorruptFileSignals() {
        assertFalse(UserCertificateCodec.isWrongPasswordFailure(IOException("unexpected end of stream")))
        assertFalse(UserCertificateCodec.isWrongPasswordFailure(Exception("DER length out of range")))
        assertFalse(UserCertificateCodec.isWrongPasswordFailure(null))
    }

    // ---- self-signed generation / pack / open ----

    @Test
    fun createSelfSigned_carriesCnAndClientAuthUsage() {
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Alice")
        assertEquals("CN=Alice", cert.subjectX500Principal.name)
        assertEquals("CN=Alice", cert.issuerX500Principal.name)
        assertTrue("private key must be RSA", key.algorithm == "RSA")
        assertTrue(
            "self-signed certificates must be usable for client auth",
            cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.2") == true,
        )
        assertTrue(
            "validity should span the configured years",
            cert.notAfter.time - cert.notBefore.time > 19L * 365L * 24 * 3600 * 1000,
        )
    }

    @Test
    fun packAndOpen_roundTripsWithWrongPasswordRejected() {
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Bob")
        val password = "s3cret".toCharArray()
        val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), password)

        // Re-serialise so the test sees the same bytes the store would write.
        val bytes = java.io.ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()

        val opened = UserCertificatePkcs12.open(bytes, password)
        assertTrue("expected the keystore to open", opened is UserCertificatePkcs12.OpenResult.Opened)
        opened as UserCertificatePkcs12.OpenResult.Opened
        assertEquals(cert.serialNumber, opened.certificate.serialNumber)

        val wrong = UserCertificatePkcs12.open(bytes, "nope".toCharArray())
        assertTrue(
            "a wrong password must not be reported as a corrupt file",
            wrong is UserCertificatePkcs12.OpenResult.WrongPassword,
        )
    }

    @Test
    fun open_reportsCorruptForGarbageBytes() {
        val result = UserCertificatePkcs12.open(ByteArray(64) { 0x41 }, "any".toCharArray())
        assertTrue(
            "non-PKCS#12 bytes must be reported as corrupt, not as a wrong password",
            result is UserCertificatePkcs12.OpenResult.Corrupt,
        )
    }

    @Test
    fun open_reportsExpiredCertificateAsUnusable() {
        val longAgo = Date(System.currentTimeMillis() - 30L * 365L * 24 * 3600 * 1000)
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Old", now = longAgo)
        val password = "pw".toCharArray()
        val bytes = java.io.ByteArrayOutputStream()
            .also { UserCertificatePkcs12.pack(key, arrayOf(cert), password).store(it, password) }
            .toByteArray()

        val result = UserCertificatePkcs12.open(bytes, password)
        assertTrue("an expired certificate must be rejected", result is UserCertificatePkcs12.OpenResult.Unusable)
        assertEquals(
            UserCertificatePkcs12.UnusableReason.EXPIRED,
            (result as UserCertificatePkcs12.OpenResult.Unusable).reason,
        )
    }

    @Test
    fun metadataOf_matchesFingerprintAndPemFormat() {
        val (_, cert) = UserCertificatePkcs12.createSelfSigned("Carol")
        val meta = UserCertificatePkcs12.metadataOf(cert)
        assertEquals(UserCertificateCodec.sha256Fingerprint(cert), meta.fingerprint)
        assertTrue("fingerprint is colon separated", meta.fingerprint.contains(":"))
        assertEquals("CN=Carol", meta.subject)
        assertEquals(cert.serialNumber.toString(16), meta.serial)
        assertTrue(meta.pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(meta.pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
        assertNotNull(meta.notAfter)
    }
}
