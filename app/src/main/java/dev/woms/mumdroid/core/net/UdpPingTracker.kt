package dev.woms.mumdroid.core.net

/**
 * Running UDP RTT accumulator for the voice channel: count, mean and
 * population variance of the measured round-trip times.
 *
 * Written by the UDP receive thread, read by the TCP ping thread
 * (buildConnectionStats) and the UI. Guarded by a lock rather than
 * atomics: the Double sum is two 32-bit halves without volatile on
 * 32-bit JVMs (torn reads), and the variance needs a consistent
 * cross-field snapshot — per-field atomics could pair a new samples
 * count with a stale sum. Lock traffic is negligible (one write per
 * ping, a few reads per second).
 */
class UdpPingTracker {

    companion object {
        /**
         * Upper bound for an accepted round-trip measurement; see [record].
         */
        const val MAX_RTT_MS = 60_000L
    }

    private val lock = Any()
    private var samples = 0
    private var total = 0L
    private var sumSq = 0.0

    /**
     * Records a measured RTT. Samples outside `[0, MAX_RTT_MS]` are rejected
     * and `false` returned: a bogus echo (malformed or spoofed) would
     * otherwise drag the average down and inflate the variance. A sane reply
     * always echoes our own timestamp, so a valid RTT lands in range.
     *
     * @return whether the sample was accepted.
     */
    fun record(rttMillis: Long): Boolean {
        if (rttMillis < 0 || rttMillis > MAX_RTT_MS) return false
        synchronized(lock) {
            samples++
            total += rttMillis
            sumSq += rttMillis.toDouble() * rttMillis
        }
        return true
    }

    /** Number of accepted round-trip measurements so far. */
    val count: Int
        get() = synchronized(lock) { samples }

    /** Mean round-trip time in milliseconds (0 when no sample yet). */
    val meanMillis: Long
        get() = synchronized(lock) {
            if (samples > 0) total / samples else 0L
        }

    /** Population variance of measured RTTs, in ms². */
    val varianceMillisSquared: Float
        get() = synchronized(lock) {
            if (samples <= 0) {
                0f
            } else {
                val mean = total.toDouble() / samples
                (sumSq / samples - mean * mean).toFloat().coerceAtLeast(0f)
            }
        }

    /** Discards all accumulated statistics. */
    fun reset() {
        synchronized(lock) {
            samples = 0
            total = 0L
            sumSq = 0.0
        }
    }
}
