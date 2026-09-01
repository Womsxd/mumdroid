package dev.woms.mumdroid.service

/** Running TCP ping RTT mean/variance, matching the UDP ping accumulator. */
internal class TcpPingStats {
    private var totalMs = 0L
    private var samples = 0
    private var sumSq = 0.0

    val sampleCount: Int get() = samples

    val averageMs: Float
        get() = if (samples > 0) totalMs.toFloat() / samples else 0f

    val averageMsLong: Long
        get() = if (samples > 0) totalMs / samples else 0L

    val variance: Float
        get() {
            if (samples <= 0) return 0f
            val mean = totalMs.toDouble() / samples
            return (sumSq / samples - mean * mean).toFloat().coerceAtLeast(0f)
        }

    fun record(rttMs: Long) {
        samples++
        totalMs += rttMs
        sumSq += rttMs.toDouble() * rttMs
    }

    fun reset() {
        totalMs = 0L
        samples = 0
        sumSq = 0.0
    }
}
