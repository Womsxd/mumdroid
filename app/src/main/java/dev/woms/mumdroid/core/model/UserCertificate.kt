package dev.woms.mumdroid.core.model

/**
 * A user (client) X.509 certificate used to authenticate the local user to a
 * Mumble server, mirroring the desktop client's certificate management.
 *
 * Unlike the server certificates held in the certificate store, this is the
 * certificate *we* present when the server asks for client authentication
 * during the TLS handshake. It is self-signed and generated locally.
 *
 * @property subject the certificate subject (the user's identity, e.g. the
 *   default username).
 * @property fingerprint the colon-separated uppercase SHA-256 fingerprint used
 *   to identify the certificate.
 * @property serial the certificate serial number as a hex string.
 * @property notBefore epoch millis when the certificate becomes valid.
 * @property notAfter epoch millis when the certificate expires.
 * @property pem the PEM-encoded certificate (public part), for display/export.
 */
data class UserCertificate(
    val subject: String,
    val fingerprint: String,
    val serial: String,
    val notBefore: Long,
    val notAfter: Long,
    val pem: String,
) {
    companion object {
        val NONE = UserCertificate("", "", "", 0, 0, "")
    }
}

/** Whether a [UserCertificate] represents an actual generated certificate. */
fun UserCertificate.isPresent(): Boolean = pem.isNotBlank()
