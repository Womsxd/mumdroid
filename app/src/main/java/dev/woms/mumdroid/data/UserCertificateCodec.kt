package dev.woms.mumdroid.data

import dev.woms.mumdroid.core.model.UserCertificate
import java.math.BigInteger
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * Pure helpers of the user-certificate store: naming/encoding rules and the
 * classification of PKCS#12 failures. No Android or storage dependency, so the
 * rules can be unit-tested directly.
 */
internal object UserCertificateCodec {

    const val CERT_FILE_PREFIX = "user_cert_"

    /** Builds the X.500 subject (`CN=…`) for a generated certificate. */
    fun subjectFor(username: String): String {
        var cn = username.trim().ifEmpty { "mumdroid-user" }
        // If the caller already passed a full subject, keep its CN.
        if (cn.startsWith("CN=")) {
            cn = cn.removePrefix("CN=").trim().ifEmpty { "mumdroid-user" }
        }
        // X.500 CN cannot contain commas/newlines; sanitise.
        val safe = cn.replace(Regex("[,\\n\\r]"), "_")
        return "CN=$safe"
    }

    /** The on-disk PKCS#12 file name for a certificate fingerprint. */
    fun certFileName(fingerprint: String): String =
        CERT_FILE_PREFIX + fingerprint.replace(":", "") + ".p12"

    /** A fresh random PKCS#12 password. */
    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** The SHA-256 fingerprint of [cert] in the usual colon-separated form. */
    fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /** The PEM encoding of [cert], wrapped at 64 characters. */
    fun toPem(cert: X509Certificate): String {
        val b64 = Base64.getEncoder().encodeToString(cert.encoded)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }

    /** The stored metadata of a certificate with a known [serial]. */
    fun metadata(cert: X509Certificate, serial: BigInteger): UserCertificate = UserCertificate(
        subject = cert.subjectDN.name,
        fingerprint = sha256Fingerprint(cert),
        serial = serial.toString(16),
        notBefore = cert.notBefore.time,
        notAfter = cert.notAfter.time,
        pem = toPem(cert),
    )

    /**
     * Whether [e] (walking its cause chain) points to a wrong PKCS#12
     * password rather than a broken file. Providers signal password failures
     * through dedicated types (UnrecoverableKeyException, padding errors from
     * the wrong decryption key) or well-known messages ("MAC verification
     * failed", "password was incorrect"). Everything else — a plain
     * IOException/EOFException, ASN.1 parse errors, truncated input — is
     * treated as a corrupt file.
     */
    fun isWrongPasswordFailure(e: Throwable?): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnrecoverableKeyException ||
                cause is BadPaddingException ||
                cause is IllegalBlockSizeException
            ) {
                return true
            }
            val msg = cause.message?.lowercase()
            if (msg != null && (msg.contains("password") || msg.contains("mac verification"))) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
