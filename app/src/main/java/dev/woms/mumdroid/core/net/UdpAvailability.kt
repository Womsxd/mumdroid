package dev.woms.mumdroid.core.net

/**
 * Official `ServerHandler::message` UDP reachability heuristic.
 *
 * The desktop client starts `bUdp` false for the TCP handshake, then restores
 * it after TLS. The 20-second grace clock is `tTimestamp` from `connectToHost`,
 * which on a PC is essentially "UDP is about to exist". On Android the TLS
 * handshake can consume most of that window, so the probe clock must start
 * when the UDP socket is actually up — otherwise a healthy voice path is
 * declared dead a few seconds after joining, murmur sees one TCP-tunneled
 * packet (`aiUdpFlag = 0`) and stops sending UDP audio.
 */
object UdpAvailability {
    const val GRACE_MS = 20_000L
    const val RESTORE_GOOD = 3

    fun shouldFallbackToTcp(
        udpAvailable: Boolean,
        forceTcp: Boolean,
        udpProbeStartMs: Long,
        nowMs: Long,
        remoteGood: Int,
        localGood: Int,
        graceMs: Long = GRACE_MS,
    ): Boolean {
        if (forceTcp || !udpAvailable) return false
        if (udpProbeStartMs <= 0L) return false
        if (nowMs - udpProbeStartMs <= graceMs) return false
        return remoteGood == 0 || localGood == 0
    }

    fun shouldRestoreUdp(
        udpAvailable: Boolean,
        forceTcp: Boolean,
        remoteGood: Int,
        localGood: Int,
        restoreGood: Int = RESTORE_GOOD,
    ): Boolean = !udpAvailable && !forceTcp && remoteGood > restoreGood && localGood > restoreGood
}
