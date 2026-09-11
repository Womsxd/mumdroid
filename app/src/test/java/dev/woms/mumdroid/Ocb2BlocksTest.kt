package dev.woms.mumdroid

import dev.woms.mumdroid.core.crypto.Ocb2Blocks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OCB2 block primitives. These carry the published-vector-sensitive part
 * of the port, so they are now testable without AES or any key state.
 */
class Ocb2BlocksTest {

    private fun block(vararg bytes: Int) = ByteArray(16) { i ->
        if (i < bytes.size) bytes[i].toByte() else 0
    }

    @Test
    fun validSlice_acceptsExactlyTheBuffer() {
        val buf = ByteArray(8)
        assertTrue(Ocb2Blocks.validSlice(buf, 0, 8))
        assertTrue(Ocb2Blocks.validSlice(buf, 8, 0))
        assertTrue(Ocb2Blocks.validSlice(buf, 0, 0))
    }

    @Test
    fun validSlice_rejectsOutOfRangeAndOverflowingSlices() {
        val buf = ByteArray(8)
        assertFalse(Ocb2Blocks.validSlice(buf, -1, 1))
        assertFalse(Ocb2Blocks.validSlice(buf, 9, 0))
        assertFalse(Ocb2Blocks.validSlice(buf, 0, -1))
        assertFalse(Ocb2Blocks.validSlice(buf, 1, 8))
        // The check must not overflow on a huge length.
        assertFalse(Ocb2Blocks.validSlice(buf, 4, Int.MAX_VALUE))
    }

    @Test
    fun xorBlock_accumulatesInPlace() {
        val dst = block(0x01, 0x02, 0x03)
        Ocb2Blocks.xorBlock(dst, 0, block(0x01, 0x02, 0x03), 0)
        assertArrayEquals(block(0x00, 0x00, 0x00), dst)
    }

    @Test
    fun twoSourceXor_writesTheDifferenceWithoutReadingDst() {
        val dst = block(0xff, 0xff)
        Ocb2Blocks.xorBlock(dst, 0, block(0x0f, 0x0f), 0, block(0x01, 0x02), 0)
        assertArrayEquals(block(0x0e, 0x0d), dst)
    }

    @Test
    fun s2_doublesWithoutReductionWhenNoCarry() {
        // Top bit clear: a plain left shift across the byte boundary.
        val b = block(0x40, 0x00, 0x00, 0x00)
        Ocb2Blocks.s2(b)
        assertEquals(0x80, b[0].toInt() and 0xff)
        assertEquals(0x00, b[1].toInt() and 0xff)
    }

    @Test
    fun s2_appliesTheReductionWhenTheTopBitCarries() {
        // The bytes are indexed low-to-high, so the bit that shifts out of
        // the whole block is bit 7 of byte 0; the reduction lands in the last
        // byte, matching libmumble's `ocb.c`.
        val b = block(0x80)
        Ocb2Blocks.s2(b)
        assertEquals(0x00, b[0].toInt() and 0xff)
        assertEquals(0x87, b[15].toInt() and 0xff)
    }

    @Test
    fun s2_shiftsEveryByteLeftByOneBit() {
        // 0x01 0x80 -> 0x03 0x00: each byte takes the top bit of its successor.
        val b = block(0x01, 0x80)
        Ocb2Blocks.s2(b)
        assertEquals(0x03, b[0].toInt() and 0xff)
        assertEquals(0x00, b[1].toInt() and 0xff)
    }

    @Test
    fun s3_isTheInPlaceXorWithItsOwnDouble() {
        val viaS3 = block(0x01, 0x80, 0x00, 0x00)
        Ocb2Blocks.s3(viaS3)

        val viaS2 = block(0x01, 0x80, 0x00, 0x00)
        val doubled = viaS2.copyOf()
        Ocb2Blocks.s2(doubled)
        for (i in viaS2.indices) {
            viaS2[i] = (viaS2[i].toInt() xor doubled[i].toInt()).toByte()
        }
        assertArrayEquals(viaS2, viaS3)
    }

    @Test
    fun s3_withNoCarry_matchesTheShiftXor() {
        val b = block(0x40, 0x00)
        Ocb2Blocks.s3(b)
        // 0x40 ^ 0x80 == 0xc0.
        assertEquals(0xc0, b[0].toInt() and 0xff)
        assertEquals(0x00, b[1].toInt() and 0xff)
    }

    @Test
    fun s2_isNotItsOwnInverse() {
        // Doubling a value that never carries out is a left shift: nine
        // doublings moving 0x01 out of bit 0 must land on 0x00, and only eight
        // doublings bring it back (bit 7 of the last byte).
        val b = block(0x01)
        repeat(7) { Ocb2Blocks.s2(b) }
        assertEquals(0x80, b[0].toInt() and 0xff)
        Ocb2Blocks.s2(b)
        // The ninth doubling carries out of the block top and applies 0x87.
        assertEquals(0x00, b[0].toInt() and 0xff)
        assertEquals(0x87, b[15].toInt() and 0xff)
    }
}
