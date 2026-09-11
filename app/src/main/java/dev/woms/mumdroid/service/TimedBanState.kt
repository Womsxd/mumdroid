package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.User
import java.util.concurrent.atomic.AtomicBoolean

/** The kick the timed-ban flow still owes the server (captured reason + flags). */
internal data class PendingKick(
    val session: Int,
    val reason: String,
    val banCertificate: Boolean,
    val banIp: Boolean,
)

/**
 * State machine behind a context-menu ban with a duration.
 *
 * A timed ban cannot be expressed on the wire, so it is assembled in two
 * replies: the kick is sent, then the `BanList` the server appends is patched
 * with the duration the UI asked for (see [TimedUserBan] for the matching
 * rules). This class owns only that bookkeeping — the sends themselves stay in
 * `ServerAdminSession`, which also exposes the resulting flows.
 *
 * The pre-ban IP comes from a `UserStats` request issued while the target is
 * still online; whichever path fires first (stats reply or kick timer) claims
 * the kick through [kickSent], so it is never sent twice.
 */
internal class TimedBanState {

    private data class Pending(
        val session: Int,
        val name: String,
        val hash: String,
        val reason: String,
        val duration: Int,
        val banCertificate: Boolean,
        val banIp: Boolean,
        val banListSnapshot: List<BanEntry>?,
    )

    private var pending: Pending? = null

    /** The target's address captured before the kick; empty when unknown. */
    @Volatile
    private var pendingAddress: ByteArray? = null

    private val kickSent = AtomicBoolean(false)

    /**
     * Arms the flow for [user]. Returns false when the caller should ban
     * immediately instead (no duration, or no user to match the reply against).
     */
    fun begin(
        session: Int,
        user: User?,
        reason: String,
        duration: Int,
        banCertificate: Boolean,
        banIp: Boolean,
        banListSnapshot: List<BanEntry>?,
    ): Boolean {
        pendingAddress = null
        pending = if (duration > 0 && user != null) {
            kickSent.set(false)
            Pending(
                session = session,
                name = user.name,
                hash = user.hash,
                reason = reason.trim(),
                duration = duration,
                banCertificate = banCertificate,
                banIp = banIp,
                banListSnapshot = banListSnapshot,
            )
        } else {
            null
        }
        return pending != null
    }

    /** Claims the kick for the delayed timer path. */
    fun sendKick(): PendingKick? {
        if (!kickSent.compareAndSet(false, true)) return null
        val p = pending ?: return null
        return PendingKick(p.session, p.reason, p.banCertificate, p.banIp)
    }

    /**
     * Claims the kick from a `UserStats` reply, recording the address murmur
     * will store in the new ban entry.
     */
    fun sendKickWithAddress(session: Int, address: ByteArray): PendingKick? {
        val p = pending ?: return null
        if (p.session != session) return null
        if (!kickSent.compareAndSet(false, true)) return null
        pendingAddress = address
        return PendingKick(p.session, p.reason, p.banCertificate, p.banIp)
    }

    /**
     * Patches the duration into the ban list the server just appended, or
     * returns null when the flow is not waiting for a list (caller then stores
     * [bans] as-is).
     */
    fun patchBanList(bans: List<BanEntry>): List<BanEntry>? {
        val p = pending ?: return null
        val patched = TimedUserBan.applyDuration(
            bans,
            p.name,
            p.hash,
            p.duration,
            p.banListSnapshot,
            pendingAddress,
        )
        pending = null
        return patched
    }

    /** Whether a removed user is the one this flow is still tracking. */
    fun matchesRemoval(session: Int, banned: Boolean): Boolean {
        val p = pending ?: return false
        return banned && p.session == session
    }

    fun reset() {
        pending = null
        pendingAddress = null
        kickSent.set(false)
    }
}
