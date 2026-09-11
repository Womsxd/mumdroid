package dev.woms.mumdroid.data

import dev.woms.mumdroid.core.model.UserCertificate
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Everything that speaks PKCS#12: building self-signed client certificates,
 * opening imported `.p12`/`.pfx` files and re-packing key material. The store
 * only decides where things are persisted; the crypto lives here.
 */
internal object UserCertificatePkcs12 {

    /** Alias of our own private-key entry inside the app's keystores. */
    const val KEY_ALIAS = "mumdroid_user_cert"

    /** Validity of generated self-signed certificates. */
    const val VALIDITY_YEARS = 20L

    /** Outcome of opening a PKCS#12 byte blob. */
    sealed interface OpenResult {
        /** A private-key entry with a usable, unexpired certificate. */
        data class Opened(
            val keyStore: KeyStore,
            val entry: KeyStore.PrivateKeyEntry,
            val certificate: X509Certificate,
        ) : OpenResult

        /** The file is a PKCS#12 but the password does not open it. */
        data object WrongPassword : OpenResult

        /** The bytes are truncated, corrupted or not PKCS#12 at all. */
        data object Corrupt : OpenResult

        /**
         * The file opened, but its content cannot be used as a client
         * certificate. [reason] is the user-facing explanation.
         */
        data class Unusable(val reason: String) : OpenResult
    }

    /**
     * Generates a self-signed RSA client certificate for [username].
     *
     * The key usage / extended key usage extensions mirror the official Mumble
     * client's self-signed certificates: strict TLS stacks verify the purpose
     * chain for client authentication and reject a leaf with a conflicting EKU
     * (SelfSignedCertificate.cpp: ext_key_usage = clientAuth).
     */
    fun createSelfSigned(
        username: String,
        now: Date = Date(),
        serial: BigInteger = BigInteger(160, SecureRandom()),
    ): Pair<PrivateKey, X509Certificate> {
        val notBefore = now
        val notAfter = Date(now.time + VALIDITY_YEARS * 365L * 24 * 3600 * 1000)

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair: KeyPair = keyGen.generateKeyPair()
        val subject = X500Name(UserCertificateCodec.subjectFor(username))

        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return keyPair.private to cert
    }

    /** Packs [key] with its [chain] into a fresh, [password]-protected keystore. */
    fun pack(key: java.security.Key, chain: Array<out java.security.cert.Certificate>, password: CharArray): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, key, password.clone(), chain)
        return ks
    }

    /**
     * Opens [p12Bytes] with [password] and classifies the outcome instead of
     * throwing, because the caller shows a different prompt per case.
     */
    fun open(p12Bytes: ByteArray, password: CharArray, now: Date = Date()): OpenResult {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ByteArrayInputStream(p12Bytes).use { input ->
                ks.load(input, password)
            }
        } catch (e: Exception) {
            // PKCS#12 loading reports a wrong password either through a
            // dedicated exception type (UnrecoverableKeyException, padding
            // errors) or through provider messages ("MAC verification
            // failed... wrong password"). A truncated / corrupted / non-PKCS12
            // file surfaces as a plain IOException or EOFException instead —
            // reporting that as "wrong password" would trap the user in a
            // useless password prompt.
            return if (UserCertificateCodec.isWrongPasswordFailure(e)) OpenResult.WrongPassword else OpenResult.Corrupt
        }

        val entry = findPrivateKeyEntry(ks, password)
            ?: return OpenResult.Unusable("证书中未找到私钥")
        val cert = entry.certificate as? X509Certificate
            ?: return OpenResult.Unusable("证书中未找到 X.509 证书")
        if (cert.notAfter.before(now)) {
            return OpenResult.Unusable("证书已过期")
        }
        return OpenResult.Opened(ks, entry, cert)
    }

    /**
     * Finds the private-key entry to use for client auth. The alias is
     * arbitrary in an imported file, so the aliases are iterated instead of
     * assuming ours.
     */
    fun findPrivateKeyEntry(ks: KeyStore, password: CharArray): KeyStore.PrivateKeyEntry? {
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!ks.isKeyEntry(alias)) continue
            val entry = try {
                ks.getEntry(alias, KeyStore.PasswordProtection(password)) as? KeyStore.PrivateKeyEntry
            } catch (e: Exception) {
                null
            }
            if (entry != null) return entry
        }
        return null
    }

    /** The stored metadata of an imported/generated [cert]. */
    fun metadataOf(cert: X509Certificate, serial: BigInteger = cert.serialNumber): UserCertificate =
        UserCertificateCodec.metadata(cert, serial)
}
