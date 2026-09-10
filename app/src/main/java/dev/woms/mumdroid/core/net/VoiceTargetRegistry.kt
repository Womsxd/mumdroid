package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.model.VoiceTargetTarget

/**
 * Allocates the numeric voice-target ids (1..30, see [VoiceTargetId]) that are
 * registered on the server for shout/whisper, and remembers which id belongs to
 * which receiver set so an id is not re-registered on every keystroke.
 *
 * ## Why an id pool at all
 * The server accepts a limited, low-numbered id range and the id rides in five
 * bits of a legacy audio packet header, so targets cannot be created freely.
 * Reusing an id for the same receiver set also spares a TCP round trip on every
 * repeat of the same target.
 *
 * ## Allocation policy
 * A fixed set of at most [capacity] slots, each holding one receiver set.
 *  - A known receiver set keeps its id (a hit costs nothing).
 *  - A new receiver set takes the lowest never-used id.
 *  - When the pool is full, the least recently used set loses its slot, and the
 *    eviction is emitted as an explicit [Op.Clear] **before** the new
 *    [Op.Register] reuses that same id. The two operations travel over the same
 *    ordered TCP control channel, so the server can never observe incoming
 *    audio tagged with an id that still carries the previous registration.
 *    (This is why no look-ahead window is needed: the stale registration is
 *    always revoked in the same ordered stream that reuses it.)
 *
 * Pure logic: no threads, no I/O. The caller turns the returned ops into
 * `VoiceTarget` messages.
 */
class VoiceTargetRegistry(
    /**
     * Number of usable ids. 30 matches the protocol ceiling; tests may lower it
     * to exercise eviction deterministically.
     */
    private val capacity: Int = VoiceTargetId.MAX - VoiceTargetId.MIN + 1,
) {
    /** A server-side registration change the caller has to send. */
    sealed interface Op {
        /** Register [target] under [id]. */
        data class Register(val id: Int, val target: VoiceTargetTarget) : Op

        /** Drop whatever is registered under [id]. */
        data class Clear(val id: Int) : Op
    }

    /**
     * The outcome of resolving a receiver set: which id outgoing audio must be
     * tagged with, and the registrations that make that id valid.
     */
    data class Resolution(val targetId: Int, val ops: List<Op>)

    /** id → receiver set currently registered under it. */
    private val assigned = HashMap<Int, VoiceTargetSpec>()

    /** Scalar recency per id; the lowest value is the eviction candidate. */
    private val recency = HashMap<Int, Long>()

    /** Monotonic counter giving recency a total order. */
    private var ticks = 0L

    /** Ids currently registered on the server. */
    val activeIds: Set<Int> get() = assigned.keys.toSet()

    /** The receiver set registered under [id], if any. */
    fun specFor(id: Int): VoiceTargetSpec? = assigned[id]

    /** The id registered for [spec], if any. */
    fun idFor(spec: VoiceTargetSpec): Int? =
        assigned.entries.firstOrNull { it.value == spec }?.key

    /**
     * Makes [spec] usable and returns the id to stamp on outgoing audio.
     *
     * @return [VoiceTargetId.NONE] when [spec] carries no receivers at all —
     *         the caller must then send nothing rather than fall back to
     *         regular speech, so an unresolvable whisper cannot become a
     *         broadcast.
     */
    fun resolve(spec: VoiceTargetSpec): Resolution {
        val target = toTarget(spec)
        if (target.isEmpty) {
            return Resolution(VoiceTargetId.NONE, emptyList())
        }

        val known = assigned.entries.firstOrNull { it.value == spec }
        if (known != null) {
            recency[known.key] = ++ticks
            return Resolution(known.key, emptyList())
        }

        val ops = ArrayList<Op>(2)
        val id = firstFreeId() ?: run {
            val victim = recency.minByOrNull { it.value }?.key ?: return Resolution(VoiceTargetId.NONE, emptyList())
            assigned.remove(victim)
            recency.remove(victim)
            ops.add(Op.Clear(victim))
            victim
        }
        assigned[id] = spec
        recency[id] = ++ticks
        ops.add(Op.Register(id, target))
        return Resolution(id, ops)
    }

    /**
     * Drops the local mapping for [spec] so the next [resolve] re-registers it,
     * returning the id that is now unaccounted for. Callers that keep the
     * server tidy must revoke that id themselves (see [Op.Clear]).
     */
    fun forget(spec: VoiceTargetSpec): Int? {
        val id = idFor(spec) ?: return null
        assigned.remove(id)
        recency.remove(id)
        return id
    }

    /**
     * Drops every registration and returns the messages that revoke them on the
     * server, so a session teardown does not leave targets behind on the server.
     */
    fun releaseAll(): List<Op> {
        val ops = assigned.keys.sorted().map { Op.Clear(it) }
        assigned.clear()
        recency.clear()
        return ops
    }

    /** Forgets all state without producing messages (fresh session). */
    fun reset() {
        assigned.clear()
        recency.clear()
        ticks = 0L
    }

    private fun firstFreeId(): Int? {
        for (id in VoiceTargetId.MIN..(VoiceTargetId.MIN + capacity - 1)) {
            if (!assigned.containsKey(id)) return id
        }
        return null
    }

    private fun toTarget(spec: VoiceTargetSpec): VoiceTargetTarget = when (spec) {
        is VoiceTargetSpec.Users -> VoiceTargetTarget(sessions = spec.sessions.distinct())
        is VoiceTargetSpec.Channel -> VoiceTargetTarget(
            channelId = spec.channelId,
            group = spec.group,
            links = spec.links,
            children = spec.children,
        )
    }
}
