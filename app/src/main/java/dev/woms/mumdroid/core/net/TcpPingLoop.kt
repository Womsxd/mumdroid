package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The client's keep-alive ping loop and its round-trip accounting, extracted
 * from [MumbleClient].
 *
 * The loop exists for two reasons: to keep a quiet control channel from being
 * dropped by NAT/keep-alive timers, and to detect a wedged server. The second
 * is why an unanswered-ping budget is tracked — the official client drops the
 * connection after `iMaxInFlightTCPPings` replies go missing.
 *
 * Timing is injectable so the in-flight/RTT rules can be tested without
 * waiting five seconds per tick.
 */
internal class TcpPingLoop(
    private val tag: String,
    /** Official `iPingIntervalMsec` default is 5 s. */
    private val intervalSeconds: Long = 5L,
    /** Official `iMaxInFlightTCPPings` default. */
    private val maxInFlight: Int = 4,
    /** Monotonic clock, like official `QElapsedTimer`. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Sends one ping carrying [timestamp]; failures are logged, not fatal. */
    private val sendPing: (timestamp: Long) -> Unit,
    /** The server is not answering: the caller must tear the connection down. */
    private val onTimeout: () -> Unit,
    /** A ping came back after [rttMillis]. */
    private val onRtt: (rttMillis: Long) -> Unit,
) {
    private var executor: ScheduledExecutorService? = null
    private var inFlight = 0

    /** Pings currently unanswered, exposed for tests and diagnostics. */
    val inFlightCount: Int get() = inFlight

    fun start() {
        stop()
        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay(
            { tick() },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    /** One scheduled tick: bail out if the budget is gone, else send and count. */
    fun tick() {
        try {
            if (inFlight >= maxInFlight) {
                onTimeout()
                return
            }
            sendPing(clock())
            inFlight++
        } catch (e: Exception) {
            // A send failure is not a reason to kill the session: the next tick
            // (or the socket's own error handling) will react.
            Log.e(tag, "Ping failed", e)
        }
    }

    /**
     * Handles a Ping reply. [timestampMs] is the echo of the value we sent, so
     * the RTT is measured against the same monotonic clock.
     *
     * Anything outside `(0, 60s)` is discarded: a zero/negative timestamp means
     * the server echoed nothing useful, and a stale one would pollute the
     * average after a long stall.
     *
     * @return the measured RTT, or null when the reply carried no usable one.
     */
    fun onReply(timestampMs: Long): Long? {
        inFlight = 0
        val now = clock()
        if (timestampMs < 1 || timestampMs >= now) return null
        val rtt = now - timestampMs
        if (rtt >= MAX_RTT_MS) return null
        onRtt(rtt)
        return rtt
    }

    /** Drops the loop; safe to call more than once. */
    fun stop() {
        executor?.shutdownNow()
        executor = null
        inFlight = 0
    }

    private companion object {
        /** Replies older than this are a stall artefact, not a usable sample. */
        const val MAX_RTT_MS = 60_000L
    }
}
