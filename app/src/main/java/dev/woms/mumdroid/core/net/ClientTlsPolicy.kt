package dev.woms.mumdroid.core.net

import android.util.Log
import dev.woms.mumdroid.core.model.CertificateDecision
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Thrown from the trust manager when the user (or a closed session) rejects
 * the server certificate while pinning is enabled. [MumbleClient] unwraps
 * this type to report a clean disconnect reason.
 */
internal class CertificateRejected(message: String) : CertificateException(message)

/**
 * One-shot gate pausing the TLS handshake thread while the user reviews a
 * certificate problem. [abort] releases a pending wait so [MumbleClient.close]
 * can never leave the connect thread blocked forever. [await] also times out
 * after [promptTimeoutSeconds] and treats that as [CertificateDecision.REJECT].
 */
internal class CertificateGate(
    /** Fallback when the UI never answers the pinning-mismatch prompt. */
    private val promptTimeoutSeconds: Long = 90L,
) {
    private val latch = CountDownLatch(1)
    private var open = false

    @Volatile
    var decision: CertificateDecision = CertificateDecision.REJECT
        private set

    /** Marks the gate as pending; called before the prompt is raised. */
    fun open() {
        synchronized(this) { open = true }
    }

    /**
     * Blocks until [resolve], [abort], or the prompt timeout, then
     * returns the decision. A timeout is [CertificateDecision.REJECT].
     */
    fun await(): CertificateDecision {
        if (!latch.await(promptTimeoutSeconds, TimeUnit.SECONDS)) {
            abort()
        }
        return decision
    }

    /** Delivers the user's decision; ignored when no prompt is pending. */
    fun resolve(d: CertificateDecision) {
        synchronized(this) {
            if (!open) return
            decision = d
            open = false
        }
        latch.countDown()
    }

    /** Releases a pending wait with [CertificateDecision.REJECT]. */
    fun abort() {
        synchronized(this) {
            if (!open) return
            decision = CertificateDecision.REJECT
            open = false
        }
        latch.countDown()
    }
}

/**
 * TLS trust policy for the TCP control connection, mirroring the
 * `certificatePinning` option of the official client:
 *
 *  - Pinning disabled: a trust-all trust manager is used, because Mumble
 *    servers commonly use self-signed certificates. The server certificate
 *    fingerprint is still captured and exposed so the UI can offer pinning /
 *    verification.
 *  - Pinning enabled: the presented certificate fingerprint must match the
 *    pinned fingerprint captured on a previous connection (the first
 *    connection itself is accepted silently and pinned by the caller). On a
 *    mismatch the handshake is paused and [onCertificateError] asks the user
 *    to update the pin, trust the certificate once, or reject the connection.
 *
 * Owns the certificate / TLS-session state exposed through [MumbleClient];
 * [reset] clears it between connections while the active pin deliberately
 * survives (the user's re-pin decision outlives one handshake).
 */
internal class ClientTlsPolicy(
    private val certificatePinning: Boolean,
    pinnedFingerprint: String?,
    private val clientCert: X509Certificate?,
    private val clientKey: java.security.PrivateKey?,
    private val onCertificateError: (
        fingerprint: String,
        pinnedFingerprint: String,
        respond: (CertificateDecision) -> Unit,
    ) -> Unit,
    /** Fallback when the UI never answers the pinning-mismatch prompt. */
    private val promptTimeoutSeconds: Long = 90L,
) {
    companion object {
        /**
         * Disconnect reason reported when the user (or a closed session)
         * rejects the server certificate during the pinning check. The
         * service maps it to a localized string.
         */
        private const val TAG = "ClientTlsPolicy"
        const val CERTIFICATE_REJECTED = "Server certificate rejected"
    }

    /**
     * The fingerprint currently pinned for this server. Starts as the pinned
     * fingerprint captured on a previous connection and is replaced when the
     * user chooses to update the pin for the rest of the session. Deliberately
     * NOT cleared by [reset]: it represents the user's pinning decision, not
     * peer session state.
     */
    @Volatile
    var activePinnedFingerprint: String? = pinnedFingerprint
        private set

    /** SHA-256 fingerprint of the server certificate, for pinning. */
    @Volatile
    var serverFingerprint: String? = null
        private set

    @Volatile
    var tlsProtocol: String = ""
        private set

    @Volatile
    var tlsCipherSuite: String = ""
        private set

    private val certificateGate = CertificateGate(promptTimeoutSeconds)

    fun createSslContext(): SSLContext {
        val trustManager: X509TrustManager = if (certificatePinning) {
            PinningTrustManager()
        } else {
            TrustAllManager
        }
        val keyManagers = clientCert?.let { cert ->
            clientKey?.let { key ->
                buildKeyManagers(cert, key)
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context
    }

    /** Captures the server fingerprint and TLS session info after the handshake. */
    fun captureSession(socket: SSLSocket) {
        try {
            val session = socket.session
            val certs = session.peerCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                serverFingerprint = sha256Fingerprint(certs[0] as X509Certificate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not capture certificate", e)
        }
        try {
            val session = socket.session
            tlsProtocol = session.protocol.orEmpty()
            tlsCipherSuite = session.cipherSuite.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read TLS session", e)
        }
    }

    /** Releases a pending certificate prompt (called from close). */
    fun abort() {
        certificateGate.abort()
    }

    /**
     * Clears peer identity captured from the TLS session. Official
     * `ServerHandler` is per-connection; these fields are the closest we
     * have, so they must not outlive the socket. The active pin survives.
     */
    fun reset() {
        serverFingerprint = null
        tlsProtocol = ""
        tlsCipherSuite = ""
    }

    /**
     * Trust-all behaviour used when certificate pinning is disabled. Mumble
     * servers commonly use self-signed certificates, so the fingerprint is
     * captured instead ([captureSession]) and verification is left to the
     * user via the pinning option.
     */
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * Trust manager implementing the `certificatePinning` option: the server
     * certificate is trusted only when its SHA-256 fingerprint matches the
     * pinned fingerprint ([activePinnedFingerprint]). The first connection
     * (no pin yet) is accepted silently so the caller can pin the captured
     * fingerprint. On a mismatch the handshake is paused and
     * [onCertificateError] asks the user to update the pin, trust the
     * certificate once, or reject the connection.
     */
    private inner class PinningTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certs = chain?.takeIf { it.isNotEmpty() }
                ?: throw CertificateException("Server did not present a certificate")
            val fingerprint = sha256Fingerprint(certs[0])
            serverFingerprint = fingerprint

            val pinned = activePinnedFingerprint?.takeIf { it.isNotBlank() } ?: return
            if (normalized(fingerprint) == normalized(pinned)) return

            // Mismatch: pause the handshake and ask the user. The gate is
            // marked open *before* the prompt so a fast user response can
            // never be lost between asking and waiting.
            certificateGate.open()
            onCertificateError(fingerprint, pinned, certificateGate::resolve)
            when (certificateGate.await()) {
                CertificateDecision.UPDATE_PIN -> {
                    // The caller re-pins the new fingerprint for future
                    // sessions; trust it for the rest of this session too.
                    activePinnedFingerprint = fingerprint
                }
                CertificateDecision.TRUST_ONCE -> Unit
                CertificateDecision.REJECT ->
                    throw CertificateRejected(CERTIFICATE_REJECTED)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Upper-case, colon-separated SHA-256 fingerprint of [cert]. */
    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /** Normalises a fingerprint for comparison (case / separator insensitive). */
    private fun normalized(fingerprint: String): String =
        fingerprint.replace(":", "").uppercase()

    /** Builds a [KeyManagerFactory] presenting the user's client certificate. */
    private fun buildKeyManagers(
        cert: X509Certificate,
        key: java.security.PrivateKey,
    ): Array<KeyManager> {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val store = java.security.KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("client", key, null, arrayOf(cert))
        factory.init(store, null)
        return factory.keyManagers
    }
}
