package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.MumbleClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Inbound TCP framing: a body is never allocated at its declared length before
 * the bytes exist, so a hostile server cannot pin the 8 MiB frame cap by
 * sending only a 6-byte header (see [MumbleClient.readMessageBody]).
 */
class MumbleMessageBodyTest {

    private val chunkBytes = 64 * 1024

    /** A stream that hands out at most [maxPerRead] bytes per call. */
    private class DripStream(private val data: ByteArray, private val maxPerRead: Int) : InputStream() {
        var consumed = 0
            private set

        override fun read(): Int {
            if (consumed >= data.size) return -1
            return data[consumed++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= data.size) return -1
            val n = minOf(len, maxPerRead, data.size - consumed)
            System.arraycopy(data, consumed, b, off, n)
            consumed += n
            return n
        }
    }

    private fun body(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    /** Reads a [size]-byte body served in [maxPerRead]-byte pieces. */
    private fun read(size: Int, maxPerRead: Int = Int.MAX_VALUE, trailing: ByteArray = ByteArray(0)): Pair<ByteArray, Int> {
        val stream = DripStream(body(size) + trailing, maxPerRead)
        val actual = MumbleClient.readMessageBody(DataInputStream(stream), size)
        return actual to stream.consumed
    }

    @Test
    fun smallBody_isExactSizeAndContent() {
        val expected = body(100)
        val (actual, consumed) = read(100)
        assertEquals(100, actual.size)
        assertArrayEquals(expected, actual)
        assertEquals(100, consumed)
    }

    @Test
    fun zeroLengthBody_consumesNothing() {
        val (actual, consumed) = read(0)
        assertEquals(0, actual.size)
        assertEquals(0, consumed)
    }

    @Test
    fun bodyUpToTheChunkSize_staysOnTheExactPath() {
        val expected = body(chunkBytes)
        val (actual, consumed) = read(chunkBytes)
        assertEquals(chunkBytes, actual.size)
        assertArrayEquals(expected, actual)
        assertEquals(chunkBytes, consumed)
    }

    @Test
    fun bodyJustOverTheChunkSize_growsOnce() {
        val expected = body(chunkBytes + 1)
        val (actual, consumed) = read(chunkBytes + 1, maxPerRead = 7)
        assertEquals(chunkBytes + 1, actual.size)
        assertArrayEquals(expected, actual)
        assertEquals(chunkBytes + 1, consumed)
    }

    @Test
    fun largeBody_survivesManyGrowthSteps() {
        val size = 300_000
        val expected = body(size)
        val (actual, consumed) = read(size, maxPerRead = 4096)
        assertEquals(size, actual.size)
        assertArrayEquals(expected, actual)
        assertEquals(size, consumed)
    }

    @Test
    fun largeBody_servedOneByteAtATime() {
        val size = chunkBytes * 2 + 5
        val expected = body(size)
        val (actual, consumed) = read(size, maxPerRead = 1)
        assertEquals(size, actual.size)
        assertArrayEquals(expected, actual)
        assertEquals(size, consumed)
    }

    @Test
    fun readStopsAtTheDeclaredSize() {
        // The next frame's header must stay in the stream: exactly `size` bytes
        // are consumed, no lookahead.
        val size = chunkBytes + 10
        val (actual, consumed) = read(size, maxPerRead = 999, trailing = byteArrayOf(0x5A))
        assertEquals(size, actual.size)
        assertEquals(size, consumed)
    }

    @Test
    fun eofMidMessage_throws() {
        val stream = DripStream(body(50), maxPerRead = 8)
        try {
            MumbleClient.readMessageBody(DataInputStream(stream), 100)
            fail("expected EOFException")
        } catch (expected: EOFException) {
            // The read loop turns this into a disconnect.
        }
    }
}
