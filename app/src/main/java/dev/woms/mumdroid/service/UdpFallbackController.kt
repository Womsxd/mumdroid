package dev.woms.mumdroid.service

import android.os.SystemClock
import dev.woms.mumdroid.core.net.UdpAvailability

/**
 * State machine for the UDP → TCP voice fallback: tracks whether the UDP
 * voice path is trusted and when the UDP probe started, and decides fallback
 * / restore transitions using the official per-ping heuristic in
 * [UdpAvailability] (`ServerHandler::message`).
 *
 * Decision→user-message mapping and the restart/reconfigure side effects
 * stay with the owner; this class is pure state + decisions and carries no
 * Android dependencies (clock injectable).
 */
internal class UdpFallbackController(
    /** Monotonic ms source for the probe grace window (official `QElapsedTimer`). */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Why the UDP path was judged unusable; drives the user-facing message. */
    enum class Reason { BOTH_DOWN, SEND_BROKEN, RECEIVE_BROKEN }

    /** A fallback/restore transition proposed by [evaluate]. */
    sealed interface Decision {
        data class Fallback(val reason: Reason) : Decision
        data object Restore : Decision
    }

    /** Whether the UDP voice path is currently trusted. */
    @Volatile
    var udpAvailable = true
        private set

    /** Monotonic ms of the moment the UDP path actually started replying. */
    @Volatile
    var probeStartMs = 0L
        private set

    /** Called when the UDP path is (re)established; arms the grace window. */
    fun markProbeStarted() {
        probeStartMs = nowMs()
    }

    /** Resets to the initial state for a new connection. */
    fun resetTo(forceTcp: Boolean) {
        udpAvailable = !forceTcp
        probeStartMs = 0L
    }

    /** Whether voice must go over the TCP tunnel (official `TcpModeEnabled || !bUdp`). */
    fun useTcp(forceTcp: Boolean): Boolean = forceTcp || !udpAvailable

    /**
     * Marks the UDP path unusable (e.g. a socket error was reported).
     *
     * @return whether the state transitioned; callers report the failure once.
     */
    fun markUnavailable(forceTcp: Boolean): Boolean {
        if (forceTcp || !udpAvailable) return false
        udpAvailable = false
        return true
    }

    /**
     * Feeds a connectivity probe result (official per-ping check).
     *
     * @return a [Decision] when the availability state changed, else null.
     */
    fun evaluate(forceTcp: Boolean, remoteGood: Int, localGood: Int): Decision? {
        if (UdpAvailability.shouldFallbackToTcp(
                udpAvailable = udpAvailable,
                forceTcp = forceTcp,
                udpProbeStartMs = probeStartMs,
                nowMs = nowMs(),
                remoteGood = remoteGood,
                localGood = localGood,
            )
        ) {
            udpAvailable = false
            return Decision.Fallback(
                when {
                    remoteGood == 0 && localGood == 0 -> Reason.BOTH_DOWN
                    remoteGood == 0 -> Reason.SEND_BROKEN
                    else -> Reason.RECEIVE_BROKEN
                },
            )
        }
        if (UdpAvailability.shouldRestoreUdp(udpAvailable, forceTcp, remoteGood, localGood)) {
            udpAvailable = true
            return Decision.Restore
        }
        return null
    }

    /** Whether the session is live but voice already fell back to TCP. */
    fun isFallbackActive(forceTcp: Boolean, live: Boolean): Boolean =
        live && !forceTcp && !udpAvailable
}
