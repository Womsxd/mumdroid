package dev.woms.mumdroid.core.crypto

import java.util.Arrays

/**
 * Implements the legacy Mumble UDP voice encryption layer, matching the
 * behaviour of the official client's `CryptState` (OCB2).
 *
 * Packet layout produced/consumed by this class:
 * ```
 * [ iv[0] (1 byte) ][ tag[0..2] (3 bytes) ][ ciphertext (== plaintext length) ]
 * ```
 * i.e. 4 bytes of overhead are prepended to the ciphertext. The 16-byte OCB2
 * nonce is incremented before every encrypt, and the first nonce byte is sent
 * in the packet so the receiver can handle packet loss/reordering.
 *
 * This class is the wire-format primitive and stays free of session policy:
 * the armed-time tracking and the official 5-second decryption-failure
 * resync rule live in [UdpVoiceCrypto] (same package).
 *
 * ## Locking / thread-safety
 *
 * Three locks, and — unlike the earlier two-lock version — a **single, fixed
 * acquisition order that is never nested in both directions**:
 *
 *  - [configurationLock] guards the whole key state: the key material of both
 *    directions, both IVs, the replay history and the packet statistics. It is
 *    the outermost lock and is the **only** lock ever acquired while holding
 *    nothing else. [setKey] and [reset] take it exclusively.
 *  - [encryptLock] / [decryptLock] guard the per-direction `setNonce` +
 *    AEAD call of [CryptOCB2], and each direction keeps its own instance, so
 *    a concurrent encrypt (capture thread) and decrypt (UDP receive thread)
 *    cannot share OCB2 state.
 *
 * Every public method follows the same rule: acquire [configurationLock]
 * first, then at most **one** direction lock. [setKey]/[reset] need to touch
 * both directions, so they mutate the shared key state under
 * [configurationLock] and take the two direction locks only *sequentially* in
 * a private helper — a nesting order that no other method can produce, which
 * makes an `encryptLock → decryptLock` / `decryptLock → encryptLock` deadlock
 * impossible by construction.
 */
class CryptState {

    private companion object {
        /** The wire header is `iv[0] | tag[0..2]`: three tag bytes travel. */
        const val WIRE_TAG_SIZE = 3
    }

    // Official CryptStateOCB2 keeps separate AES contexts for encrypt and
    // decrypt. A single shared CryptOCB2 races when the capture thread encrypts
    // while the UDP receive thread decrypts (both call setNonce).
    private val encCrypt = CryptOCB2()
    private val decCrypt = CryptOCB2()
    private val configurationLock = Any()
    private val encryptLock = Any()
    private val decryptLock = Any()
    private val encryptTag = ByteArray(CryptOCB2.BLOCK_SIZE)

    /**
     * Receive-path scratch, so a datagram allocates no header bookkeeping:
     * the decryption nonce as it was before the header byte was applied
     * (restored for rejected and late packets) and the three wire tag bytes.
     * Both are only touched under [decryptLock].
     */
    private val decryptSaveIv = ByteArray(CryptOCB2.NONCE_SIZE)
    private val decryptWireTag = ByteArray(WIRE_TAG_SIZE)

    /** The current encryption/decryption nonce (16 bytes). */
    @Volatile
    private var encryptNonce = ByteArray(CryptOCB2.NONCE_SIZE)

    @Volatile
    private var decryptNonce = ByteArray(CryptOCB2.NONCE_SIZE)

    private val replayHistory = CryptReplayHistory()

    /** good / late / lost / resync counters of [decrypt] and the CryptSetup resync. */
    private val packetStats = CryptPacketStats()

    /** Packet statistics (good / late / lost / resync), mirroring the official
     *  `CryptState::m_statsLocal` so they can be reported to the server in the
     *  TCP Ping message (the PC admin's user info shows them).
     *
     *  Volatile: [decrypt] updates them on the UDP thread while the TCP Ping
     *  reads them from the control-channel thread to decide UDP→TCP fallback
     *  (they are also published as a consistent snapshot through [stats]). */
    @Volatile
    var goodPackets: Int = 0
        private set
    @Volatile
    var latePackets: Int = 0
        private set
    @Volatile
    var lostPackets: Int = 0
        private set
    @Volatile
    var resyncPackets: Int = 0
        private set

    /** Copies the counter bag into the volatile fields the readers watch. */
    private fun publishStats() {
        goodPackets = packetStats.good
        latePackets = packetStats.late
        lostPackets = packetStats.lost
        resyncPackets = packetStats.resync
    }

    /**
     * Published readiness. [setKey]/[reset] write it on the control thread;
     * TCP ping / capture / UDP receive read it unlocked (`isCryptoReady`,
     * send-path guards). The direction locks do not cover those reads, so
     * this must be volatile or ARM can keep a stale `false` after CryptSetup
     * (or a stale `true` after teardown) for a long time.
     *
     * It is written last (true) / first (false) inside [configurationLock], so
     * a `true` observation implies the re-armed AEAD contexts and nonces are
     * already visible to a thread that only reads this flag.
     */
    @Volatile
    var isReady: Boolean = false
        private set

    /** A consistent read of the four packet counters, so the caller cannot
     *  snapshot a half-updated set (decrypt updates several counters). */
    data class Stats(val good: Int, val late: Int, val lost: Int, val resync: Int)

    /** @return a consistent snapshot of the packet counters, taken under
     *  [configurationLock] so a concurrent [setKey]/[reset] zeroing cannot be
     *  observed half-applied. */
    fun stats(): Stats = synchronized(configurationLock) {
        publishStats()
        Stats(goodPackets, latePackets, lostPackets, resyncPackets)
    }

    /** Initialises the key and the initial nonces. */
    fun setKey(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Boolean {
        if (key.size != CryptOCB2.KEY_SIZE) return false
        if (clientNonce.size != CryptOCB2.NONCE_SIZE) return false
        if (serverNonce.size != CryptOCB2.NONCE_SIZE) return false
        synchronized(configurationLock) {
            // CryptOCB2.setKey is false when AES/ECB init fails. Swallowing
            // that would leave isReady true while encrypt() writes 0 bytes.
            if (!encCrypt.setKey(key) || !decCrypt.setKey(key)) {
                encCrypt.clearKeys()
                decCrypt.clearKeys()
                isReady = false
                return false
            }
            // A full key delivery starts a fresh crypto context (official
            // `CryptState::setKey` memsets the replay history): a stale
            // history from a previous session or key rotation could
            // otherwise reject valid packets whose IV byte collides with
            // an old (byte0 -> byte1) entry.
            replayHistory.clear()
            packetStats.reset()
            publishStats()
            encryptNonce = clientNonce.copyOf()
            decryptNonce = serverNonce.copyOf()
            if (!encCrypt.setNonce(encryptNonce) || !decCrypt.setNonce(decryptNonce)) {
                encCrypt.clearKeys()
                decCrypt.clearKeys()
                isReady = false
                return false
            }
            // Fully initialised. Re-arm the direction contexts (the AES
            // schedules were built above, outside the direction locks) and
            // only then publish readiness: [decrypt] re-checks `isReady`
            // under [decryptLock], and that lock is the happens-before edge
            // that makes the new key schedule visible to the UDP thread.
            // The two direction locks are taken sequentially, never nested,
            // so the lock order stays acyclic.
            rearmDirectionCiphers()
            isReady = true
        }
        return true
    }

    /**
     * Re-creates the reused AES block ciphers of both directions without
     * nesting the direction locks (which would re-introduce the
     * `encryptLock → decryptLock` order that [decrypt]/[encrypt] must stay
     * independent of). Callers hold [configurationLock] in write mode.
     */
    private fun rearmDirectionCiphers() {
        synchronized(decryptLock) {
            decCrypt.rearmCiphers()
        }
        synchronized(encryptLock) {
            encCrypt.rearmCiphers()
        }
    }

    /**
     * Current encryption IV (client nonce) for CryptSetup resync replies.
     * The snapshot is taken under [configurationLock], the lock that also
     * covers [reset]'s wipe, so a concurrent teardown cannot hand out an IV
     * after [isReady] has already gone false.
     */
    fun getEncryptIV(): ByteArray? = synchronized(configurationLock) {
        if (!isReady) null else encryptNonce.copyOf()
    }

    /**
     * Increments [resyncPackets]. Official `msgCryptSetup` does
     * `m_statsLocal.resync++` immediately before [setDecryptIV]; this method
     * is that increment. Full key delivery goes through [setKey] and must
     * not call this.
     */
    fun incrementResync() {
        synchronized(configurationLock) {
            packetStats.onResync()
            publishStats()
        }
    }

    /**
     * Replaces the decryption IV (mirrors `CryptStateOCB2::setDecryptIV`).
     * Mutates under [configurationLock] first and only then takes
     * [decryptLock] for the `setNonce` that [decrypt] also needs, so a
     * concurrent [setKey] can never leave the decryption nonce half-adopted.
     * Official crypto does not count a resync here: the caller
     * (`Messages.cpp` `msgCryptSetup`) increments first. Call [incrementResync]
     * before this for a server-nonce CryptSetup resync.
     */
    fun setDecryptIV(iv: ByteArray): Boolean {
        if (iv.size != CryptOCB2.NONCE_SIZE) return false
        synchronized(configurationLock) {
            val newNonce = iv.copyOf()
            synchronized(decryptLock) {
                decryptNonce = newNonce
                decCrypt.setNonce(newNonce)
            }
        }
        return true
    }

    /**
     * Encrypts [source] into a newly allocated legacy Mumble voice packet.
     * Prefer [encrypt] with a caller buffer on the audio send path.
     */
    fun encrypt(source: ByteArray): ByteArray? = encrypt(source, 0, source.size)

    /**
     * Encrypts `source[offset, offset+length)` into a newly allocated packet.
     */
    fun encrypt(source: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < 0) return null
        val packet = ByteArray(4 + length)
        val n = encrypt(source, offset, length, packet, 0)
        return if (n < 0) null else packet
    }

    /**
     * Official `CryptStateOCB2::encrypt(source, dst, plain_length)`:
     * writes `[iv0|tag0..2|ciphertext]` into [dest] at [destOffset].
     *
     * @return datagram length (`4 + length`), or -1 on failure. Does not
     * increment the nonce when the destination is too small.
     */
    fun encrypt(
        source: ByteArray,
        dest: ByteArray,
        destOffset: Int = 0,
    ): Int = encrypt(source, 0, source.size, dest, destOffset)

    fun encrypt(
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
        dest: ByteArray,
        destOffset: Int = 0,
    ): Int {
        if (!isReady) return -1
        if (sourceOffset < 0 || length < 0 || sourceOffset > source.size) return -1
        if (length > source.size - sourceOffset) return -1
        if (destOffset < 0 || destOffset > dest.size) return -1
        if (4 + length > dest.size - destOffset) return -1
        // Serialise this direction only: a concurrent decrypt/teardown must not
        // pair a nonce of one key generation with the key of another.
        synchronized(encryptLock) {
            if (!isReady || !encCrypt.isReady) return -1
            CryptNonce.increment(encryptNonce)
            encCrypt.setNonce(encryptNonce)
            val written = encCrypt.encrypt(
                dest, source, encryptTag,
                inputOffset = sourceOffset,
                inputLength = length,
                outputOffset = destOffset + 4,
            )
            if (written != length) return -1
            dest[destOffset] = encryptNonce[0]
            dest[destOffset + 1] = encryptTag[0]
            dest[destOffset + 2] = encryptTag[1]
            dest[destOffset + 3] = encryptTag[2]
            return 4 + length
        }
    }

    /**
     * Decrypts a legacy Mumble voice packet.
     * @return the plaintext, or null if authentication failed.
     */
    fun decrypt(packet: ByteArray): ByteArray? = decrypt(packet, 0, packet.size)

    /**
     * Decrypts `packet[offset, offset+length)` without copying the receive
     * buffer first. [offset] points at the 4-byte OCB2 header
     * (`iv[0] | tag[0..2]`), not at the ciphertext.
     */
    fun decrypt(packet: ByteArray, offset: Int, length: Int): ByteArray? {
        if (!isReady || length < 4) return null
        if (offset < 0 || offset > packet.size) return null
        if (length > packet.size - offset) return null
        synchronized(decryptLock) {
            // Re-check under the direction lock: a concurrent [setKey]/[reset]
            // may have flipped isReady while this packet was in flight, and
            // the decryption nonce must not be advanced against wiped key
            // material (outside the lock the volatile read alone cannot
            // guarantee that the neighbouring checks see the same generation).
            if (!isReady || !decCrypt.isReady) return null
            val ivByte = packet[offset].toInt() and 0xff
            val cipherLength = length - 4

            System.arraycopy(decryptNonce, 0, decryptSaveIv, 0, CryptOCB2.NONCE_SIZE)
            val plan = CryptNonce.plan(decryptNonce, ivByte)
            if (plan.advance == CryptNonce.Advance.UNKNOWN) return null
            if (plan.advance == CryptNonce.Advance.DUPLICATE) return null
            plan.apply(decryptNonce)
            val replayChecked = plan.advance != CryptNonce.Advance.IN_ORDER
            if (replayChecked &&
                replayHistory.isReplay(decryptNonce[0].toInt() and 0xff, decryptNonce[1])
            ) {
                restoreDecryptNonce()
                return null
            }

            decCrypt.setNonce(decryptNonce)
            // The caller owns the returned array, so it is allocated here. Note
            // that the receive path does NOT keep this array: the framing layer
            // copies the payload out of it, so the plaintext dies within the
            // receive call and a caller could instead pass a reusable scratch
            // buffer ([CryptOCB2.decrypt] already accepts an output buffer).
            // [decrypt] stays allocation-per-call because it returns an array.
            val plain = ByteArray(cipherLength)
            // Official `memcmp(tag, source+1, 3)` / libmumble `User::decrypt`
            // passes the 3 header bytes, not a 16-byte zero buffer. Comparing
            // a full 16-byte tag against zeros made every UDP datagram fail
            // auth (force-TCP still worked because the tunnel is plaintext).
            System.arraycopy(packet, offset + 1, decryptWireTag, 0, WIRE_TAG_SIZE)
            val written = decCrypt.decrypt(
                plain, packet, decryptWireTag,
                inputOffset = offset + 4,
                inputLength = cipherLength,
            )

            if (written < 0) {
                restoreDecryptNonce()
                return null
            }

            replayHistory.remember(decryptNonce[0].toInt() and 0xff, decryptNonce[1])
            packetStats.onDecrypted(plan.lost, plan.late)
            publishStats()

            // For late packets we must not persist the temporarily advanced nonce:
            // restore it so subsequently arriving in-order packets still decrypt
            // correctly (mirrors the official `CryptStateOCB2::decrypt`).
            if (plan.restore) {
                restoreDecryptNonce()
            }

            return plain
        }
    }

    /**
     * Restores [decryptNonce] to the bytes saved before the header byte was
     * applied. Written in place: `CryptNonce.Plan.apply` mutates the nonce
     * array itself, so replacing the reference is not needed and would have to
     * allocate. Only called under [decryptLock].
     */
    private fun restoreDecryptNonce() {
        System.arraycopy(decryptSaveIv, 0, decryptNonce, 0, CryptOCB2.NONCE_SIZE)
    }

    fun reset() {
        synchronized(configurationLock) {
            // Fail closed: publish "not ready" before wiping, so a concurrent
            // encrypt/decrypt that is still inside a direction lock bounces off
            // its re-check instead of running against wiped key material.
            isReady = false
            // Wipe the key material and IVs so no stale session state survives
            // a teardown.
            synchronized(decryptLock) {
                decCrypt.clearKeys()
            }
            synchronized(encryptLock) {
                encCrypt.clearKeys()
            }
            Arrays.fill(encryptNonce, 0)
            Arrays.fill(decryptNonce, 0)
            replayHistory.clear()
            Arrays.fill(encryptTag, 0)
            Arrays.fill(decryptSaveIv, 0)
            packetStats.reset()
            publishStats()
        }
    }
}
