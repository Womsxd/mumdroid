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
 */
class CryptState {

    // Official CryptStateOCB2 keeps separate AES contexts for encrypt and
    // decrypt. A single shared CryptOCB2 races when the capture thread encrypts
    // while the UDP receive thread decrypts (both call setNonce).
    private val encCrypt = CryptOCB2()
    private val decCrypt = CryptOCB2()
    private val encryptLock = Any()
    private val decryptLock = Any()

    /** The current encryption/decryption nonce (16 bytes). */
    var encryptNonce = ByteArray(CryptOCB2.NONCE_SIZE)
        private set

    private var decryptNonce = ByteArray(CryptOCB2.NONCE_SIZE)

    private val decryptHistory = ByteArray(256)

    /** Packet statistics (good / late / lost / resync), mirroring the official
     *  `CryptState::m_statsLocal` so they can be reported to the server in the
     *  TCP Ping message (the PC admin's user info shows them).
     *
     *  Volatile: decrypt runs on the UDP thread, TCP Ping reads these from
     *  the control-channel thread to decide UDP→TCP fallback. */
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

    var isReady: Boolean = false
        private set

    /** Initialises the key and the initial nonces. */
    fun setKey(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Boolean {
        if (key.size != CryptOCB2.KEY_SIZE) return false
        if (clientNonce.size != CryptOCB2.NONCE_SIZE) return false
        if (serverNonce.size != CryptOCB2.NONCE_SIZE) return false
        synchronized(encryptLock) {
            synchronized(decryptLock) {
                encCrypt.setKey(key)
                decCrypt.setKey(key)
                // A full key delivery starts a fresh crypto context (official
                // `CryptState::setKey` memsets the replay history): a stale
                // history from a previous session or key rotation could
                // otherwise reject valid packets whose IV byte collides with
                // an old (byte0 -> byte1) entry.
                Arrays.fill(decryptHistory, 0)
                goodPackets = 0
                latePackets = 0
                lostPackets = 0
                encryptNonce = clientNonce.copyOf()
                decryptNonce = serverNonce.copyOf()
                encCrypt.setNonce(encryptNonce)
                decCrypt.setNonce(decryptNonce)
                isReady = true
            }
        }
        return true
    }

    /** The current encryption IV (client nonce), for CryptSetup resync replies. */
    fun getEncryptIV(): ByteArray = synchronized(encryptLock) { encryptNonce.copyOf() }

    /** Replaces the encryption IV (mirrors `CryptStateOCB2::setEncryptIV`). */
    fun setEncryptIV(iv: ByteArray): Boolean {
        if (iv.size != CryptOCB2.NONCE_SIZE) return false
        synchronized(encryptLock) {
            encryptNonce = iv.copyOf()
            encCrypt.setNonce(encryptNonce)
        }
        return true
    }

    /**
     * Increments [resyncPackets]. Official `msgCryptSetup` does
     * `m_statsLocal.resync++` immediately before [setDecryptIV]; this method
     * is that increment. Full key delivery goes through [setKey] and must
     * not call this.
     */
    fun incrementResync() {
        synchronized(decryptLock) {
            resyncPackets++
        }
    }

    /**
     * Replaces the decryption IV (mirrors `CryptStateOCB2::setDecryptIV`).
     * Official crypto does not count a resync here: the caller
     * (`Messages.cpp` `msgCryptSetup`) increments first. Call [incrementResync]
     * before this for a server-nonce CryptSetup resync.
     */
    fun setDecryptIV(iv: ByteArray): Boolean {
        if (iv.size != CryptOCB2.NONCE_SIZE) return false
        synchronized(decryptLock) {
            decryptNonce = iv.copyOf()
            decCrypt.setNonce(decryptNonce)
        }
        return true
    }

    /**
     * Encrypts [source] into a legacy Mumble voice packet.
     * Ciphertext is written directly into the returned datagram at offset 4
     * so the 4-byte header does not need a second copy of the payload.
     * @return the complete packet (overhead + ciphertext), or null on failure.
     */
    fun encrypt(source: ByteArray): ByteArray? = encrypt(source, 0, source.size)

    /**
     * Encrypts `source[offset, offset+length)` into a legacy Mumble voice packet.
     */
    fun encrypt(source: ByteArray, offset: Int, length: Int): ByteArray? {
        if (!isReady) return null
        if (offset < 0 || length < 0 || offset > source.size) return null
        if (length > source.size - offset) return null
        synchronized(encryptLock) {
            incrementNonce(encryptNonce)
            encCrypt.setNonce(encryptNonce)

            val packet = ByteArray(4 + length)
            val tag = ByteArray(CryptOCB2.BLOCK_SIZE)
            val written = encCrypt.encrypt(
                packet, source, tag,
                inputOffset = offset,
                inputLength = length,
                outputOffset = 4,
            )
            if (written != length) return null

            packet[0] = encryptNonce[0]
            packet[1] = tag[0]
            packet[2] = tag[1]
            packet[3] = tag[2]
            return packet
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
            val ivByte = packet[offset].toInt() and 0xff
            val cipherLength = length - 4

            val saveIv = decryptNonce.copyOf()
            var restore = false
            var lost = 0
            var late = 0

            // Advance the decryption nonce based on the received IV byte.
            if (((decryptNonce[0].toInt() + 1) and 0xff) == ivByte) {
                if (ivByte > (decryptNonce[0].toInt() and 0xff)) {
                    decryptNonce[0] = ivByte.toByte()
                } else if (ivByte < (decryptNonce[0].toInt() and 0xff)) {
                    decryptNonce[0] = ivByte.toByte()
                    for (i in 1 until decryptNonce.size) {
                        val v = (decryptNonce[i].toInt() and 0xff) + 1
                        decryptNonce[i] = (v and 0xff).toByte()
                        if (v != 0x100) break
                    }
                } else {
                    return null
                }
            } else {
                var diff = ivByte - (decryptNonce[0].toInt() and 0xff)
                if (diff > 128) diff -= 256
                else if (diff < -128) diff += 256

                when {
                    ivByte < (decryptNonce[0].toInt() and 0xff) && diff > -30 && diff < 0 -> {
                        // Late packet, but no wraparound.
                        late = 1
                        lost = -1
                        restore = true
                        decryptNonce[0] = ivByte.toByte()
                    }
                    ivByte > (decryptNonce[0].toInt() and 0xff) && diff > -30 && diff < 0 -> {
                        // Late packet from the previous round (wraparound).
                        late = 1
                        lost = -1
                        restore = true
                        decryptNonce[0] = ivByte.toByte()
                        for (i in 1 until decryptNonce.size) {
                            val v = (decryptNonce[i].toInt() and 0xff) - 1
                            decryptNonce[i] = (v and 0xff).toByte()
                            if (v != -1) break
                        }
                    }
                    ivByte > (decryptNonce[0].toInt() and 0xff) && diff > 0 -> {
                        lost = ivByte - (decryptNonce[0].toInt() and 0xff) - 1
                        decryptNonce[0] = ivByte.toByte()
                    }
                    ivByte < (decryptNonce[0].toInt() and 0xff) && diff > 0 -> {
                        lost = 256 - (decryptNonce[0].toInt() and 0xff) + ivByte - 1
                        decryptNonce[0] = ivByte.toByte()
                        for (i in 1 until decryptNonce.size) {
                            val v = (decryptNonce[i].toInt() and 0xff) + 1
                            decryptNonce[i] = (v and 0xff).toByte()
                            if (v != 0x100) break
                        }
                    }
                    else -> return null
                }

                if (decryptHistory[decryptNonce[0].toInt() and 0xff] == decryptNonce[1]) {
                    decryptNonce = saveIv
                    return null
                }
            }

            decCrypt.setNonce(decryptNonce)
            val plain = ByteArray(cipherLength)
            // Official `memcmp(tag, source+1, 3)` / libmumble `User::decrypt`
            // passes the 3 header bytes, not a 16-byte zero buffer. Comparing
            // a full 16-byte tag against zeros made every UDP datagram fail
            // auth (force-TCP still worked because the tunnel is plaintext).
            val wireTag = byteArrayOf(packet[offset + 1], packet[offset + 2], packet[offset + 3])
            val written = decCrypt.decrypt(
                plain, packet, wireTag,
                inputOffset = offset + 4,
                inputLength = cipherLength,
            )

            if (written < 0) {
                decryptNonce = saveIv
                return null
            }

            decryptHistory[decryptNonce[0].toInt() and 0xff] = decryptNonce[1]
            updateStats(lost, late)

            // For late packets we must not persist the temporarily advanced nonce:
            // restore it so subsequently arriving in-order packets still decrypt
            // correctly (mirrors the official `CryptStateOCB2::decrypt`).
            if (restore) {
                decryptNonce = saveIv
            }

            return plain
        }
    }

    /** Updates the good/late/lost packet counters (mirrors the official
     *  `CryptStateOCB2::decrypt` bookkeeping). */
    private fun updateStats(lost: Int, late: Int) {
        goodPackets++
        if (late > 0) {
            latePackets += late
        } else if (latePackets > Math.abs(late)) {
            latePackets += late
        }
        if (lost > 0) {
            lostPackets += lost
        } else if (lostPackets > Math.abs(lost)) {
            lostPackets += lost
        }
    }

    private fun incrementNonce(nonce: ByteArray) {
        for (i in nonce.indices) {
            val v = (nonce[i].toInt() and 0xff) + 1
            nonce[i] = (v and 0xff).toByte()
            if (v != 0x100) break
        }
    }

    fun reset() {
        synchronized(encryptLock) {
            synchronized(decryptLock) {
                isReady = false
                // Fail closed: wipe the key material and IVs so no stale
                // session state survives a teardown.
                encCrypt.clearKeys()
                decCrypt.clearKeys()
                Arrays.fill(encryptNonce, 0)
                Arrays.fill(decryptNonce, 0)
                Arrays.fill(decryptHistory, 0)
                goodPackets = 0
                latePackets = 0
                lostPackets = 0
                resyncPackets = 0
            }
        }
    }
}
