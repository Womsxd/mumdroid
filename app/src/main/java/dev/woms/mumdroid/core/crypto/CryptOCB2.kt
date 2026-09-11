package dev.woms.mumdroid.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * An implementation of the OCB2 authenticated-encryption mode used by Mumble
 * to encrypt/authenticate UDP voice packets.
 *
 * This implementation follows the algorithm from the official Mumble/libmumble
 * sources (BSD-3-Clause), ported to pure JVM/Android using the standard
 * `AES/ECB/NoPadding` cipher that is always available on Android. It does not
 * depend on third-party crypto providers.
 *
 * Thread-safety contract: a single instance is NOT safe for concurrent use.
 * [CryptState] keeps separate instances for each direction and serialises
 * every call of one instance under its encrypt/decrypt lock, which is also
 * why the reused [Cipher] objects below need no further synchronisation.
 *
 * `isReady` is deliberately **not** `@Volatile`: it is only read/written while
 * the owning direction lock is held (`CryptState.encrypt`/`decrypt`,
 * `setKey`/`reset`), and that same lock is what publishes a key change to the
 * other thread. Cross-thread publication of "crypto usable" is
 * `CryptState.isReady`, which is volatile.
 */
class CryptOCB2 {

    companion object {
        const val BLOCK_SIZE = 16
        const val KEY_SIZE = 16
        const val NONCE_SIZE = 16

        private const val TRANSFORMATION = "AES/ECB/NoPadding"
    }

    private val key = ByteArray(KEY_SIZE)
    private val nonce = ByteArray(NONCE_SIZE)
    private val random = SecureRandom()

    /**
     * Reused AES block ciphers (one per direction). The OCB2 key only changes
     * on a CryptSetup, so the key schedule is built once per key in [setKey]
     * instead of a `Cipher.getInstance` + `SecretKeySpec` + `init` round-trip
     * for every block of every voice packet. Per the JCE contract, `doFinal`
     * resets the cipher to its post-init state, making repeated reuse sound.
     */
    private var encryptCipher: Cipher? = null
    private var decryptCipher: Cipher? = null

    private var keySet = false
    private var nonceSet = false

    /**
     * Whether the key and nonce have been set on this instance.
     * Only read/written under [CryptState]'s matching direction lock, so
     * this is not the cross-thread publication flag — that is
     * [CryptState.isReady]. Volatile would be redundant here.
     */
    var isReady: Boolean = false
        private set

    /**
     * Re-creates the reused AES block ciphers for the current [key] and
     * preserves [isReady] (`nonceSet` is untouched). Used by
     * [CryptState.setKey] to give the owning direction lock a happens-before
     * edge on the freshly built key schedule without nesting the two
     * direction locks — the cipher fields are plain (non-volatile) fields, so
     * the re-publication must happen under the lock that serialises the
     * direction's use of them.
     *
     * @return false when AES could not be initialised; the caller then has an
     *         unusable direction (isReady stays false).
     */
    fun rearmCiphers(): Boolean {
        if (!initCiphers()) {
            keySet = false
            isReady = false
            return false
        }
        keySet = true
        isReady = keySet && nonceSet
        return true
    }

    fun setKey(newKey: ByteArray): Boolean {
        if (newKey.size != KEY_SIZE) return false
        System.arraycopy(newKey, 0, key, 0, KEY_SIZE)
        if (!initCiphers()) {
            keySet = false
            isReady = false
            return false
        }
        keySet = true
        isReady = keySet && nonceSet
        return true
    }

    /** Builds the two block ciphers for the current [key]. */
    private fun initCiphers(): Boolean {
        return try {
            val spec = SecretKeySpec(key, "AES")
            encryptCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, spec)
            }
            decryptCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, spec)
            }
            true
        } catch (e: GeneralSecurityException) {
            // `AES/ECB/NoPadding` is guaranteed by the platform, so this is
            // unreachable in practice; fail closed anyway.
            encryptCipher = null
            decryptCipher = null
            false
        }
    }

    fun setNonce(newNonce: ByteArray): Boolean {
        if (newNonce.size != NONCE_SIZE) return false
        System.arraycopy(newNonce, 0, nonce, 0, NONCE_SIZE)
        nonceSet = true
        isReady = keySet && nonceSet
        return true
    }

    fun generateKey(): ByteArray {
        val k = ByteArray(KEY_SIZE)
        random.nextBytes(k)
        return k
    }

    fun generateNonce(): ByteArray {
        val n = ByteArray(NONCE_SIZE)
        random.nextBytes(n)
        return n
    }

    /**
     * Zeroes the key/nonce material and drops the reused ciphers, so no
     * secret state survives a teardown (fail closed: [isReady] stays false
     * until [setKey] succeeds again).
     */
    fun clearKeys() {
        key.fill(0)
        nonce.fill(0)
        encryptCipher = null
        decryptCipher = null
        keySet = false
        nonceSet = false
        isReady = false
    }

    /**
     * Encrypts [input] in OCB2 mode, writing the ciphertext to [output] and the
     * authentication tag to [tag] (which must be 16 bytes).
     *
     * [inputOffset]/[inputLength] select a slice of [input]; [outputOffset]
     * is where ciphertext begins in [output] (so a caller can leave a 4-byte
     * Mumble header in front without an extra copy).
     *
     * @return the number of ciphertext bytes written.
     */
    fun encrypt(
        output: ByteArray,
        input: ByteArray,
        tag: ByteArray,
        inputOffset: Int = 0,
        inputLength: Int = input.size - inputOffset,
        outputOffset: Int = 0,
    ): Int {
        if (!isReady) return 0
        if (tag.isNotEmpty() && tag.size != BLOCK_SIZE) return 0
        // Official `ocb_encrypt` treats len=0 as valid (blank tag
        // BF310813… in `TestCrypt::testvectors`). Only negative lengths fail.
        if (inputLength < 0) return 0
        if (!Ocb2Blocks.validSlice(input, inputOffset, inputLength)) return 0
        if (!Ocb2Blocks.validSlice(output, outputOffset, inputLength)) return 0

        val delta = aesBlock(nonce) ?: return 0
        var written = 0

        val checksum = ByteArray(BLOCK_SIZE)
        val tmp = ByteArray(BLOCK_SIZE)

        val inputEnd = inputOffset + inputLength
        var inOffset = inputOffset
        while (inputEnd - inOffset > BLOCK_SIZE) {
            var flipABit = false
            if (inputEnd - inOffset - BLOCK_SIZE <= BLOCK_SIZE) {
                var sum = 0
                for (i in 0 until BLOCK_SIZE - 1) {
                    sum = sum or (input[inOffset + i].toInt() and 0xff)
                }
                if (sum == 0) flipABit = true
            }

            Ocb2Blocks.s2(delta)
            Ocb2Blocks.xorBlock(tmp, 0, delta, 0, input, inOffset)

            if (flipABit) {
                tmp[0] = (tmp[0].toInt() xor 1).toByte()
            }

            val enc = aesBlock(tmp) ?: return 0
            Ocb2Blocks.xorBlock(output, outputOffset + written, delta, 0, enc, 0)
            written += BLOCK_SIZE

            Ocb2Blocks.xorBlock(checksum, 0, input, inOffset)
            if (flipABit) {
                checksum[0] = (checksum[0].toInt() xor 1).toByte()
            }

            inOffset += BLOCK_SIZE
        }

        Ocb2Blocks.s2(delta)

        val len = inputEnd - inOffset
        tmp.fill(0)
        tmp[BLOCK_SIZE - 1] = (len * 8).toByte()
        Ocb2Blocks.xorBlock(tmp, 0, delta, 0)

        val pad = aesBlock(tmp) ?: return 0

        // tmpBytes = plaintext || pad_tail (matching the reference algorithm)
        tmp.fill(0)
        System.arraycopy(input, inOffset, tmp, 0, len)
        System.arraycopy(pad, len, tmp, len, BLOCK_SIZE - len)
        // checksum ^= (plaintext || pad_tail)
        Ocb2Blocks.xorBlock(checksum, 0, tmp, 0)
        // tmp = pad ^ tmpBytes -> (pad[:len]^plain) || 0
        Ocb2Blocks.xorBlock(tmp, 0, pad, 0)

        System.arraycopy(tmp, 0, output, outputOffset + written, len)
        written += len

        if (tag.isNotEmpty()) {
            Ocb2Blocks.s3(delta)
            Ocb2Blocks.xorBlock(tmp, 0, delta, 0, checksum, 0)
            val computedTag = aesBlock(tmp) ?: return 0
            System.arraycopy(computedTag, 0, tag, 0, BLOCK_SIZE)
        }

        return written
    }

    /**
     * Decrypts [input] in OCB2 mode, authenticating against [tag].
     * Only the leading `tag.size` bytes are compared (libmumble
     * `std::equal(tag.begin(), tag.end(), retrievedTag)`), because the
     * Mumble wire header carries 3 tag bytes.
     *
     * [inputOffset]/[inputLength] select a slice of [input] so the 4-byte
     * Mumble UDP header can stay in the receive buffer.
     *
     * @return the number of plaintext bytes written, or -1 on failure.
     */
    fun decrypt(
        output: ByteArray,
        input: ByteArray,
        tag: ByteArray,
        inputOffset: Int = 0,
        inputLength: Int = input.size - inputOffset,
    ): Int {
        if (!isReady) return -1
        if (inputLength < 0 || !Ocb2Blocks.validSlice(input, inputOffset, inputLength)) return -1
        if (!Ocb2Blocks.validSlice(output, 0, inputLength)) return -1

        val delta = aesBlock(nonce) ?: return -1
        var written = 0

        val checksum = ByteArray(BLOCK_SIZE)
        val tmp = ByteArray(BLOCK_SIZE)

        val inputEnd = inputOffset + inputLength
        var inOffset = inputOffset
        while (inputEnd - inOffset > BLOCK_SIZE) {
            Ocb2Blocks.s2(delta)
            Ocb2Blocks.xorBlock(tmp, 0, delta, 0, input, inOffset)
            val dec = aesBlockDecrypt(tmp) ?: return -1
            Ocb2Blocks.xorBlock(output, written, delta, 0, dec, 0)
            written += BLOCK_SIZE

            Ocb2Blocks.xorBlock(checksum, 0, output, written - BLOCK_SIZE)

            inOffset += BLOCK_SIZE
        }

        Ocb2Blocks.s2(delta)

        val len = inputEnd - inOffset
        tmp.fill(0)
        tmp[BLOCK_SIZE - 1] = (len * 8).toByte()
        Ocb2Blocks.xorBlock(tmp, 0, delta, 0)

        val pad = aesBlock(tmp) ?: return -1

        // tmpBytes = ciphertext || zeros
        tmp.fill(0)
        System.arraycopy(input, inOffset, tmp, 0, len)
        // tmp = pad ^ tmpBytes -> plaintext || pad_tail
        Ocb2Blocks.xorBlock(tmp, 0, pad, 0)

        // Counter-cryptanalysis described in section 9 of https://eprint.iacr.org/2019/311
        // In an attack, the decrypted last block would need to equal `delta ^ len(128)`.
        // Since our `len` only ever modifies the last byte, we only compare the
        // remaining BLOCK_SIZE - 1 bytes (mirrors the official implementation).
        var equal = true
        for (i in 0 until BLOCK_SIZE - 1) {
            if (tmp[i].toInt() != delta[i].toInt()) {
                equal = false
                break
            }
        }
        if (equal) return -1

        // checksum ^= (plaintext || pad_tail)
        Ocb2Blocks.xorBlock(checksum, 0, tmp, 0)

        System.arraycopy(tmp, 0, output, written, len)
        written += len

        if (tag.isNotEmpty()) {
            Ocb2Blocks.s3(delta)
            Ocb2Blocks.xorBlock(tmp, 0, delta, 0, checksum, 0)
            val computedTag = aesBlock(tmp) ?: return -1
            // libmumble `std::equal(tag.begin(), tag.end(), retrievedTag)`:
            // the wire header only carries 3 tag bytes (`CryptStateOCB2`).
            if (tag.size > computedTag.size) return -1
            for (i in tag.indices) {
                if (tag[i] != computedTag[i]) return -1
            }
        }

        return written
    }


    // ---- AES block helpers ----

    /**
     * One AES block. Returns null instead of throwing when the cipher was
     * dropped ([clearKeys] / failed [setKey]) so a future refactor cannot
     * crash the audio thread with [IllegalStateException].
     */
    private fun aesBlock(input: ByteArray): ByteArray? {
        val cipher = encryptCipher ?: return null
        return try {
            cipher.doFinal(input)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun aesBlockDecrypt(input: ByteArray): ByteArray? {
        val cipher = decryptCipher ?: return null
        return try {
            cipher.doFinal(input)
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
