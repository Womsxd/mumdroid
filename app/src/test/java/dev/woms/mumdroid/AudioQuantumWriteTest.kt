package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.writeQuantumToTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Short-write and failure handling of the playback quantum.
 *
 * `AudioTrack.write` may queue less than the whole quantum — it returns a short
 * count when the track is stopped or paused underneath it — and the old
 * playback loop ignored that count: the tail of the quantum was dropped, a zero
 * return passed as success without even a log, and a negative return was
 * retried every 5 ms forever while the jitter buffer drained and the output
 * stayed silent.
 */
class AudioQuantumWriteTest {

    private val quantum = 960

    private fun frame() = ShortArray(quantum) { (it + 1).toShort() }

    @Test
    fun fullWrite_queuesTheQuantumInOneCallWithoutCopying() {
        val playback = frame()
        val calls = mutableListOf<Pair<Int, Int>>()
        val tapped = mutableListOf<ShortArray>()

        val queued = writeQuantumToTrack(playback, { tapped.add(it) }) { offset, count ->
            calls.add(offset to count)
            count
        }

        assertEquals(quantum, queued)
        assertEquals(listOf(0 to quantum), calls)
        // A full write taps the buffer itself: no per-quantum copy on the hot path.
        assertEquals(1, tapped.size)
        assertSame(playback, tapped.single())
    }

    @Test
    fun shortWrites_completeTheQuantumFromTheAdvancingOffset() {
        val playback = frame()
        val counts = listOf(400, 300, 260) // sums to the quantum
        val offsets = mutableListOf<Int>()
        var next = 0

        val queued = writeQuantumToTrack(playback, null) { offset, _ ->
            offsets.add(offset)
            counts[next++]
        }

        assertEquals(quantum, queued)
        assertEquals(listOf(0, 400, 700), offsets)
    }

    @Test
    fun shortWrites_tapExactlyTheSlicesThatWereQueued() {
        val playback = frame()
        val tapped = mutableListOf<ShortArray>()
        var next = 0

        writeQuantumToTrack(playback, { tapped.add(it) }) { _, _ ->
            if (next++ == 0) 400 else quantum - 400
        }

        assertEquals(2, tapped.size)
        assertArrayEquals(playback.copyOfRange(0, 400), tapped[0])
        assertArrayEquals(playback.copyOfRange(400, quantum), tapped[1])
    }

    @Test
    fun zeroProgress_stopsAfterOneCallInsteadOfSpinning() {
        val playback = frame()
        var calls = 0

        val queued = writeQuantumToTrack(playback, null) { _, _ ->
            calls++
            0
        }

        assertEquals(0, queued)
        assertEquals(1, calls)
    }

    @Test
    fun negativeErrorCode_stopsAndReportsTheCode() {
        val playback = frame()
        var calls = 0

        // AudioTrack.ERROR_DEAD_OBJECT: the audio server restarted, the track
        // cannot be recovered by writing again.
        val queued = writeQuantumToTrack(playback, null) { _, _ ->
            calls++
            -6
        }

        assertEquals(-6, queued)
        assertEquals(1, calls)
    }

    @Test
    fun releasedTrack_propagatesIllegalStateExceptionToTheCaller() {
        val playback = frame()

        assertThrows(IllegalStateException::class.java) {
            writeQuantumToTrack(playback, null) { _, _ ->
                throw IllegalStateException("track released")
            }
        }
    }
}
