package dev.woms.mumdroid.core.crypto

import dev.woms.mumdroid.core.crypto.Ocb2Blocks.REDUCTION
import dev.woms.mumdroid.core.crypto.Ocb2Blocks.s2


/**
 * The block-level primitives of OCB2 (RFC 7253 / libmumble `ocb.c`):
 * GF(2^128) doubling and its `s2`/`s3` variants, plus the small slice and XOR
 * helpers the encrypt/decrypt cores are written in terms of.
 *
 * Split out of [CryptOCB2] because these are the parts of the port that can be
 * checked against the published test vectors in isolation, with no AES or key
 * state involved.
 */
internal object Ocb2Blocks {

    /** 128-bit block size. */
    const val BLOCK_SIZE = 16

    /** The reduction polynomial's low byte, applied when the doubling carries out. */
    private const val REDUCTION = 0x87

    /** True when `[offset, offset+length)` lies inside [buf] (overflow-safe). */
    fun validSlice(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 0 || offset > buf.size) return false
        return length <= buf.size - offset
    }

    /** `dst[dstOffset..] ^= a[aOffset..]`, one block. */
    fun xorBlock(dst: ByteArray, dstOffset: Int, a: ByteArray, aOffset: Int) {
        for (i in 0 until BLOCK_SIZE) {
            dst[dstOffset + i] = (dst[dstOffset + i].toInt() xor a[aOffset + i].toInt()).toByte()
        }
    }

    /** `dst[dstOffset..] = a[aOffset..] ^ b[bOffset..]`, one block. */
    fun xorBlock(
        dst: ByteArray,
        dstOffset: Int,
        a: ByteArray,
        aOffset: Int,
        b: ByteArray,
        bOffset: Int,
    ) {
        for (i in 0 until BLOCK_SIZE) {
            dst[dstOffset + i] = (a[aOffset + i].toInt() xor b[bOffset + i].toInt()).toByte()
        }
    }

    /**
     * Official `S2`: in-place GF(2^128) double (left shift + [REDUCTION] when
     * the top bit carries out). Walks low-to-high so `block[i+1]` is still
     * unread when `block[i]` is overwritten; no scratch copy. Instances are
     * used serially under a direction lock (see [CryptOCB2]).
     */
    fun s2(block: ByteArray) {
        val carry = (block[0].toInt() and 0xff) ushr 7
        for (i in 0 until BLOCK_SIZE - 1) {
            block[i] = (((block[i].toInt() and 0xff) shl 1) or ((block[i + 1].toInt() and 0xff) ushr 7)).toByte()
        }
        block[BLOCK_SIZE - 1] =
            (((block[BLOCK_SIZE - 1].toInt() and 0xff) shl 1) xor (carry * REDUCTION)).toByte()
    }

    /**
     * Official `S3`: in-place `block ^= s2(block)`. Same low-to-high walk as
     * [s2]; the XOR keeps the original byte while the shift still needs it.
     */
    fun s3(block: ByteArray) {
        val carry = (block[0].toInt() and 0xff) ushr 7
        for (i in 0 until BLOCK_SIZE - 1) {
            val shifted =
                ((block[i].toInt() and 0xff) shl 1) or ((block[i + 1].toInt() and 0xff) ushr 7)
            block[i] = (block[i].toInt() xor shifted).toByte()
        }
        val shiftedLast =
            ((block[BLOCK_SIZE - 1].toInt() and 0xff) shl 1) xor (carry * REDUCTION)
        block[BLOCK_SIZE - 1] = (block[BLOCK_SIZE - 1].toInt() xor shiftedLast).toByte()
    }
}
