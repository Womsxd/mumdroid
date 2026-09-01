package dev.woms.mumdroid.core.model

/**
 * A pending server-certificate problem raised while certificate pinning was
 * enabled: the fingerprint presented by the server does not match the one
 * pinned for this server. The connection is paused until the user decides to
 * update the pin, trust the certificate once, or reject the connection.
 *
 * @property fingerprint the SHA-256 fingerprint the server presented.
 * @property pinnedFingerprint the fingerprint previously pinned for this
 *   server (empty when the server is unknown to the store).
 * @property host the server host the prompt belongs to.
 * @property port the server port the prompt belongs to.
 */
data class CertificatePrompt(
    val fingerprint: String,
    val pinnedFingerprint: String,
    val host: String,
    val port: Int,
)
