package dev.woms.mumdroid.ui.screen

import dev.woms.mumdroid.core.model.ServerPingInfo

/** Latency buckets of the server card, matching the desktop client's colours. */
internal enum class LatencyGrade { FAST, MEDIUM, SLOW }

/** Health of a ping snapshot as far as the card is concerned. */
internal enum class PingHealth { PROBING, UNREACHABLE, NO_LATENCY, MEASURED }

/**
 * Pure presentation decisions of [ServerCard], kept out of the composable so
 * they can be unit-tested without a Compose runtime.
 */
internal object ServerPingDisplay {

    /** How many of the three latency bars light up. */
    fun litBars(ping: ServerPingInfo?): Int {
        if (ping == null || ping.probing || !ping.reachable) return 0
        val ms = ping.pingMs ?: return 1
        // FAST lights all three, each slower grade one bar less.
        return LatencyGrade.entries.size - grade(ms).ordinal
    }

    fun grade(ms: Int): LatencyGrade = when {
        ms < 50 -> LatencyGrade.FAST
        ms < 150 -> LatencyGrade.MEDIUM
        else -> LatencyGrade.SLOW
    }

    fun health(ping: ServerPingInfo?): PingHealth = when {
        ping == null || ping.probing -> PingHealth.PROBING
        !ping.reachable -> PingHealth.UNREACHABLE
        ping.pingMs == null -> PingHealth.NO_LATENCY
        else -> PingHealth.MEASURED
    }
}
