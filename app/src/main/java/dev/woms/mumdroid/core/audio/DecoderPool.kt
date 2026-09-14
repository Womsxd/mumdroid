package dev.woms.mumdroid.core.audio

/**
 * Per-session decoder cache shared by the Opus backends.
 *
 * Tracks when each session was last seen, drops decoders idle longer than
 * [ttlMs] and trims the least-recently-used ones down to [maxDecoders].
 *
 * The sweep is gated by time *and* by the number of newly seen sessions. Two
 * earlier gate designs failed here: keying it on the map size being a multiple
 * of 128 never fired while 33..127 sessions stayed alive (and left the TTL
 * branch unreachable), and an ungated counter without a lock lost updates under
 * concurrent decodes. Both counters and maps are therefore mutated under
 * [lock], which also gives the clock reads and the eviction a happens-before
 * edge; [onEvict] runs while the lock is held, so it must not call back into
 * the pool.
 */
internal class DecoderPool<T : Any>(
    private val maxDecoders: Int = DEFAULT_MAX_DECODERS,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val sweepAfterNewSessions: Int = DEFAULT_SWEEP_AFTER_NEW_SESSIONS,
    // Monotonic (immune to NTP steps and user clock changes) yet
    // framework-free: this pool is shared with the pure-Java Concentus backend
    // that JVM unit tests exercise directly, where an android.os.SystemClock
    // call would be an unmocked framework call (see app/build.gradle.kts
    // testOptions). System.nanoTime is CLOCK_MONOTONIC, the same base as the
    // official QElapsedTimer the other voice clocks mirror.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvict: (T) -> Unit = {},
) {

    companion object {
        const val DEFAULT_MAX_DECODERS = 32
        const val DEFAULT_TTL_MS = 30_000L
        const val DEFAULT_SWEEP_INTERVAL_MS = 10_000L
        const val DEFAULT_SWEEP_AFTER_NEW_SESSIONS = 16
    }

    private val lock = Any()
    private val decoders = HashMap<Int, T>()
    private val lastUse = HashMap<Int, Long>()
    private var lastSweepMs = 0L

    /**
     * Explicit rather than a zero sentinel: a monotonic clock has an arbitrary
     * origin (`System.nanoTime` can even read negative) and tests certainly
     * read 0, so `lastSweepMs == 0L` says nothing about whether a sweep has
     * happened and would leave the time gate open until `sweepIntervalMs`
     * elapsed from that origin.
     */
    private var sweepPrimed = false
    private var newSessionsSinceSweep = 0

    /** Number of live decoders. */
    val size: Int get() = synchronized(lock) { decoders.size }

    /**
     * Returns the decoder for [session], creating it with [create] when absent,
     * and refreshes its last-use stamp. A sweep runs first when it is due, so
     * the cache converges to [maxDecoders] instead of growing with every
     * speaker ever heard.
     *
     * @return the decoder, or null when [create] reports failure; a failed
     *         session leaves no stale last-use entry behind.
     */
    fun acquire(session: Int, create: () -> T?): T? = synchronized(lock) {
        val now = clock()
        // Prime on first use so the interval is measured from then rather than
        // from an unset baseline.
        if (!sweepPrimed) {
            sweepPrimed = true
            lastSweepMs = now
        }
        if (lastUse.put(session, now) == null) newSessionsSinceSweep++
        if (sweepDue(now)) sweep(now)
        decoders[session]?.let { return it }
        val created = create() ?: run {
            lastUse.remove(session)
            return null
        }
        decoders[session] = created
        created
    }

    /** The decoder for [session], without creating one or touching timestamps. */
    fun get(session: Int): T? = synchronized(lock) { decoders[session] }

    /** Whether [session] currently has a decoder. */
    fun contains(session: Int): Boolean = synchronized(lock) { decoders.containsKey(session) }

    /** Drops every decoder, invoking [onEvict] for each. */
    fun clear() = synchronized(lock) {
        val snapshot = decoders.values.toList()
        decoders.clear()
        lastUse.clear()
        snapshot.forEach(onEvict)
    }

    private fun sweepDue(now: Long): Boolean =
        now - lastSweepMs >= sweepIntervalMs ||
            newSessionsSinceSweep >= sweepAfterNewSessions

    private fun sweep(now: Long) {
        lastSweepMs = now
        newSessionsSinceSweep = 0
        val it = lastUse.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > ttlMs) {
                decoders.remove(e.key)?.let(onEvict)
                it.remove()
            }
        }
        if (decoders.size <= maxDecoders) return
        val excess = decoders.size - maxDecoders
        val oldest = lastUse.entries.sortedBy { it.value }.take(excess)
        for (e in oldest) {
            decoders.remove(e.key)?.let(onEvict)
            lastUse.remove(e.key)
        }
    }
}
