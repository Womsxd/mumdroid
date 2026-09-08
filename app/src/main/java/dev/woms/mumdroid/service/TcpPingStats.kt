package dev.woms.mumdroid.service

/**
 * Running TCP ping RTT mean/variance, matching the UDP ping accumulator.
 *
 * Written from the TCP read loop (`tcpPingListener`), read from the ping
 * timer thread (`statsProvider`) and the UI. Guarded by a lock rather than
 * atomics: the Double sum is two 32-bit halves without volatile on 32-bit
 * JVMs (torn reads), and the variance needs a consistent cross-field
 * snapshot — per-field atomics could pair a new samples count with a stale
 * sum. Lock traffic is negligible (one write per ping, a few reads per
 * second).
 */
internal class TcpPingStats {
    private val lock = Any()
    private var totalMs = 0L
    private var samples = 0
    private var sumSq = 0.0

    val sampleCount: Int get() = synchronized(lock) { samples }

    val averageMs: Float
        get() = synchronized(lock) {
            if (samples > 0) totalMs.toFloat() / samples else 0f
        }

    val averageMsLong: Long
        get() = synchronized(lock) {
            if (samples > 0) totalMs / samples else 0L
        }

    val variance: Float
        get() = synchronized(lock) {
            if (samples <= 0) {
                0f
            } else {
                val mean = totalMs.toDouble() / samples
                (sumSq / samples - mean * mean).toFloat().coerceAtLeast(0f)
            }
        }

    fun record(rttMs: Long) {
        synchronized(lock) {
            samples++
            totalMs += rttMs
            sumSq += rttMs.toDouble() * rttMs
        }
    }

    fun reset() {
        synchronized(lock) {
            totalMs = 0L
            samples = 0
            sumSq = 0.0
        }
    }
}
