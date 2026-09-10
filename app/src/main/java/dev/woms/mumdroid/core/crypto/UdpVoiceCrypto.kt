package dev.woms.mumdroid.core.crypto

import android.os.SystemClock
import dev.woms.mumdroid.core.crypto.UdpVoiceCrypto.Companion.RESYNC_AFTER_MS

/**
 * Voice-channel crypto policy around [CryptState]: arms the OCB2 key material
 * and enforces the official crypto-failure recovery, mirroring how the
 * official client's `ServerHandler` reacts to CryptSetup and its UDP decoder
 * reacts to decryption failures:
 *
 *  - CryptSetup full delivery = fresh crypto context (replay history and
 *    packet statistics cleared),
 *  - CryptSetup resync delivery = `m_statsLocal.resync++` then `setDecryptIV`,
 *  - when decryption keeps failing for [RESYNC_AFTER_MS] a nonce resync is
 *    requested via [onRequestCryptResync] (official 5-second `tLastGood`
 *    rule; the baseline starts when the crypto is armed, not at first
 *    success, so a never-successful decrypt still resyncs after 5 s).
 *
 * Layering: [CryptState] is the wire-format primitive (the official
 * CryptStateOCB2 port) and stays free of session policy; this class is the
 * policy wrapper and deliberately carries no transport (core/net)
 * dependencies.
 *
 * Threading: the methods below are called from different threads ([setup] /
 * [resyncDecryptIV] from the TCP read loop, [decrypt] from the UDP receive
 * thread, [reset] from the service coroutine) and are all safe to interleave
 * — [CryptState] guards its key state with a single configuration lock and
 * keeps the per-direction locks for the AEAD calls, so a CryptSetup can never
 * be torn across a concurrent decrypt (see [CryptState]'s KDoc).
 */
class UdpVoiceCrypto(
    /** Monotonic ms source (official `QElapsedTimer`). */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        /** If decryption keeps failing for this long, request a crypt resync. */
        private const val RESYNC_AFTER_MS = 5000L
    }

    /** Legacy OCB2 voice-packet counters (good/late/lost/resync). */
    data class CryptStats(val good: Int, val late: Int, val lost: Int, val resync: Int)

    /**
     * Invoked when UDP decryption keeps failing (official 5-second rule); the
     * owner should send an empty CryptSetup over TCP to request a nonce
     * resync from the server.
     */
    @Volatile
    var onRequestCryptResync: (() -> Unit)? = null

    private val crypt = CryptState()

    /** Official `tLastGood` starts when crypto is armed, not at first success. */
    @Volatile
    private var cryptoReadyMs = 0L

    /** Monotonic ms of the last successful UDP decryption (for resync detection). */
    @Volatile
    private var lastGoodUdpMs = 0L

    /** Monotonic ms of the last crypt-resync request we issued. */
    @Volatile
    private var lastResyncRequestMs = 0L

    /** @return whether the OCB2 crypto state is ready to encrypt/decrypt. */
    val isReady: Boolean
        get() = crypt.isReady

    /**
     * Sets the OCB2 key and the client/server nonces from CryptSetup.
     * A full delivery is a fresh crypto context: [CryptState.setKey] clears
     * the replay history and packet statistics, so key rotations and
     * re-delivered setups cannot inherit state from a previous session.
     */
    fun setup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        if (!crypt.setKey(key, clientNonce, serverNonce)) return
        if (cryptoReadyMs == 0L) cryptoReadyMs = clock()
    }

    /**
     * Adopts a new decryption IV delivered via CryptSetup resync.
     * Mirrors official client `msgCryptSetup`: size check, then
     * `m_statsLocal.resync++`, then `setDecryptIV`. Full key delivery
     * must go through [setup] instead.
     */
    fun resyncDecryptIV(iv: ByteArray): Boolean {
        if (iv.size != CryptOCB2.NONCE_SIZE) return false
        crypt.incrementResync()
        return crypt.setDecryptIV(iv)
    }

    /** The current encryption IV, or null when crypto is not ready. */
    fun encryptIV(): ByteArray? = crypt.getEncryptIV()

    /** @return the legacy OCB2 packet statistics (good/late/lost/resync)
     *          accumulated by the decrypt path, so they can be reported in the
     *          TCP Ping (the PC admin's user info shows them).
     *
     *          Read from the control-channel thread while the UDP thread may be
     *          updating the counters, so the four values are taken as one
     *          snapshot from [CryptState.stats] instead of four independent
     *          volatile reads (which could mix two generations). */
    fun packetStats(): CryptStats = crypt.stats().let {
        CryptStats(good = it.good, late = it.late, lost = it.lost, resync = it.resync)
    }

    /**
     * Decrypts a complete voice datagram (`[4-byte OCB2 overhead][ciphertext]`)
     * and returns the plaintext `[header|payload]`, or null on failure.
     * Mirrors the official client's 5-second rule: when decryption keeps
     * failing, a crypt-nonce resync is requested via [onRequestCryptResync].
     */
    fun decrypt(packet: ByteArray, offset: Int, length: Int): ByteArray? {
        val plain = crypt.decrypt(packet, offset, length)
        if (plain != null) {
            lastGoodUdpMs = clock()
            lastResyncRequestMs = 0L
        } else if (isReady) {
            val now = clock()
            // Official tLastGood starts at construction, so a never-successful
            // decrypt still resyncs after 5 s. lastGoodUdpMs==0 used to skip that.
            val baseline = if (lastGoodUdpMs != 0L) lastGoodUdpMs else cryptoReadyMs
            if (baseline != 0L && now - baseline > RESYNC_AFTER_MS) {
                val lastRequest = lastResyncRequestMs
                if (lastRequest == 0L || now - lastRequest > RESYNC_AFTER_MS) {
                    lastResyncRequestMs = now
                    onRequestCryptResync?.invoke()
                }
            }
        }
        return plain
    }

    /** Encrypts [plain] into [output]; @return the ciphertext length, or negative on failure. */
    fun encrypt(plain: ByteArray, output: ByteArray): Int = crypt.encrypt(plain, output)

    /**
     * Full teardown: wipe the OCB2 key/history/stats so no crypto state
     * survives across sessions. The datagram stop path deliberately does NOT
     * call this — crypto must survive for the TCP-tunnel fallback.
     */
    fun reset() {
        crypt.reset()
        lastGoodUdpMs = 0L
        cryptoReadyMs = 0L
        lastResyncRequestMs = 0L
    }
}
