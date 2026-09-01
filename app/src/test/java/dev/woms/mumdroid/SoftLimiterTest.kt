package dev.woms.mumdroid

import dev.woms.mumdroid.core.audio.SoftLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoftLimiterTest {

    @Test
    fun belowKnee_isIdentity() {
        // Everything up to ~-3 dBFS must pass through completely untouched.
        for (v in intArrayOf(0, 1, -1, 1000, -1000, 20000, -20000)) {
            assertEquals(v.toDouble(), SoftLimiter.limit(v.toDouble()), 0.0)
        }
    }

    @Test
    fun output_neverExceedsFullScale() {
        // Even extreme over-drive must saturate below Short.MAX_VALUE.
        for (v in doubleArrayOf(32767.0, 40000.0, 60000.0, 100000.0, 1e9)) {
            assertTrue(SoftLimiter.limit(v) <= Short.MAX_VALUE)
            assertTrue(SoftLimiter.limit(-v) >= -Short.MAX_VALUE.toDouble())
        }
    }

    @Test
    fun transferFunction_isContinuousAndMonotonic() {
        var previous = SoftLimiter.limit(0.0)
        var v = 0.0
        while (v <= 120000.0) {
            val out = SoftLimiter.limit(v)
            // Monotonically non-decreasing.
            assertTrue("non-monotonic at input $v", out >= previous - 1e-9)
            // No jumps larger than one LSB per sample step.
            assertTrue("discontinuity at input $v", abs(out - previous) < 2.0)
            previous = out
            v += 1.0
        }
    }

    @Test
    fun symmetric() {
        for (v in doubleArrayOf(25000.0, 30000.0, 50000.0, 98765.5)) {
            val pos = SoftLimiter.limit(v)
            val neg = SoftLimiter.limit(-v)
            assertEquals(pos, -neg, 1e-9)
        }
    }

    @Test
    fun boostedHotSignal_compressesInsteadOfClippingToSquareWave() {
        // A near-full-scale sine amplified by 2x: every sample would hard-clip
        // into a square wave. The limiter must keep peaks just under full scale
        // while preserving most of the level (no massive loss).
        val peak = 28000.0
        val boosted = SoftLimiter.limit(peak * 2.0) // 56000 -> clipped region
        assertTrue("boosted peak should stay near full scale, got $boosted", boosted > 30000.0)
        assertTrue(boosted <= Short.MAX_VALUE)

        // A quiet signal boosted by 2x stays untouched (below the knee).
        assertEquals(4000.0 * 2.0, SoftLimiter.limit(4000.0 * 2.0), 0.0)
    }
}
