package dev.woms.mumdroid.core.audio

import kotlin.math.abs

/**
 * A C1-continuous soft limiter for 16-bit PCM samples.
 *
 * Samples below the knee point (~-3 dBFS) pass through unchanged; peaks above
 * the knee are smoothly compressed toward full scale with a slope matched at
 * the knee, so boosting a hot signal produces gentle compression instead of
 * the harsh harmonic distortion ("杂音") of hard clipping.
 */
object SoftLimiter {

    /** Where the compression knee starts (~70% of full scale / -3 dBFS). */
    private const val KNEE = Short.MAX_VALUE * 0.7

    private const val CEILING = Short.MAX_VALUE.toDouble()

    private const val SPAN = CEILING - KNEE

    /**
     * Maps a (possibly over-full-scale) float sample onto [-32767, 32767]
     * without ever exceeding the ceiling. The transfer function is the identity
     * up to the knee and then saturates like x/(1+x), which has unit slope at
     * the knee (no level jump) and an asymptote exactly at the ceiling.
     */
    fun limit(sample: Double): Double {
        val magnitude = abs(sample)
        if (magnitude <= KNEE) return sample
        val x = (magnitude - KNEE) / SPAN
        val compressed = SPAN * (x / (1.0 + x))
        val limited = KNEE + compressed
        return if (sample < 0.0) -limited else limited
    }
}
