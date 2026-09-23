package dev.woms.mumdroid.core.net

import android.annotation.SuppressLint
import android.util.Log
import dev.woms.mumdroid.core.model.CertificateDecision
import dev.woms.mumdroid.core.net.ClientTlsPolicy.Companion.MIN_TLS_PROTOCOLS
import dev.woms.mumdroid.core.net.ClientTlsPolicy.Companion.enabledProtocolsFor
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
 * survives (the user's re-pin decision outlives one handshake). The handshake
 * offers TLS 1.2 or newer — the official client's `QSsl::TlsV1_2OrLater` —
 * plus TLS 1.0/1.1 when [allowLegacyTls] asks for them; [applyProtocols] is
 * what puts that set on the socket.
 */
internal class ClientTlsPolicy(
    private val certificatePinning: Boolean,
    pinnedFingerprint: String?,
    private val clientCert: X509Certificate?,
    private val clientKey: java.security.PrivateKey?,
    /**
     * Opt-in escape hatch for servers that only speak TLS 1.0/1.1, from the
     * "Allow legacy TLS" setting. Bounded by the platform: where it no longer
     * supports those versions (Android 15+), [enabledProtocolsFor] drops them
     * again and the connection stays on the TLS 1.2 floor.
     */
    private val allowLegacyTls: Boolean = false,
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

        /**
         * The versions the control channel may negotiate: the official
         * client's `QSsl::TlsV1_2OrLater` (`ServerHandler::run`; murmur sets
         * the same floor in `Server::sslSocket`). The set is built explicitly
         * rather than left to the platform default, which varies by release,
         * vendor and target SDK.
         */
        private val MIN_TLS_PROTOCOLS = listOf("TLSv1.2", "TLSv1.3")

        /**
         * Added to [MIN_TLS_PROTOCOLS] only when the user allows legacy TLS
         * ([ClientTlsPolicy.allowLegacyTls]), for servers that support nothing
         * newer.
         */
        private val LEGACY_TLS_PROTOCOLS = listOf("TLSv1", "TLSv1.1")

        /**
         * [supported] intersected with the versions above, in the platform's
         * own order.
         *
         * The intersection is what may be handed to `setEnabledProtocols`:
         * Conscrypt rejects anything outside `getSupportedProtocols()` with an
         * `IllegalArgumentException` (it stopped silently dropping them for
         * apps targeting Android 15+), so TLS 1.3 — which only exists from
         * Android 10 (API 29) on — and the legacy versions on a platform that
         * disallows them are filtered out here instead. Intersecting also
         * means SSLv3 can never come back into the set.
         *
         * @throws IllegalArgumentException when the intersection is empty, i.e.
         *   the platform can speak nothing this policy accepts. See below.
         */
        internal fun enabledProtocolsFor(
            supported: Array<String>,
            allowLegacyTls: Boolean,
        ): Array<String> {
            val allowed = if (allowLegacyTls) {
                MIN_TLS_PROTOCOLS + LEGACY_TLS_PROTOCOLS
            } else {
                MIN_TLS_PROTOCOLS
            }
            val usable = supported.filter { it in allowed }.toTypedArray()
            // An empty set must fail here, naming this policy, rather than reach
            // `setEnabledProtocols`: Conscrypt then aborts the handshake with
            // "No enabled protocols", which points at its own internals instead
            // of at the version floor this app chose. No device can get here
            // (minSdk 26 always offers TLS 1.2), but the diagnostic is cheap.
            require(usable.isNotEmpty()) {
                "Platform offers no acceptable TLS version for the control channel " +
                    "(supported: ${supported.joinToString()}, allowed: ${allowed.joinToString()})"
            }
            return usable
        }
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

    /**
     * Puts the protocol set from [enabledProtocolsFor] on the socket, so a
     * server offering a weaker version than the settings allow cannot
     * downgrade the control channel. Must run before the handshake.
     */
    fun applyProtocols(socket: SSLSocket) {
        socket.enabledProtocols = enabledProtocolsFor(socket.supportedProtocols, allowLegacyTls)
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
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
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
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
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
