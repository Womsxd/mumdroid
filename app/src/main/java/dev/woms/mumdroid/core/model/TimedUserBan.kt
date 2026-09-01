package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.net.BanEntry

/**
 * `UserRemove` has no duration field; Murmur always stores `iDuration = 0`.
 * Timed context-menu bans are applied afterwards by patching the BanList
 * entry the server just appended.
 *
 * Identification signals, most precise first:
 *
 *  1. **Pre-ban IP** (`pendingAddress`): the service asks for the target's
 *     `UserStats.address` while the user is still online, and murmur stores
 *     exactly those bytes in the new `BanEntry.address` — an entry with a
 *     different address is never ours, no matter the name.
 *  2. **Snapshot diff**: the ban list right before the kick vs. the reply;
 *     entries missing from the snapshot are what the server appended.
 *     Concurrent admins may append further entries, so the diff only ranks
 *     candidates.
 *  3. **Loose name/hash match** (fallback when neither IP nor snapshot is
 *     available, e.g. the list had never loaded): Murmur query replies clear
 *     `query`, IP-only bans have an empty hash, and certificate-only bans
 *     have an empty address.
 *
 * Protocol limit: two same-named certificate-less users sharing one IP (or
 * both banned with no IP captured) produce identical entries — murmur itself
 * cannot tell them apart (`BanList` carries no session id).
 */
object TimedUserBan {
    fun applyDuration(
        bans: List<BanEntry>,
        name: String,
        hash: String,
        durationSeconds: Int,
        previousBans: List<BanEntry>? = null,
        pendingAddress: ByteArray? = null,
    ): List<BanEntry>? {
        if (durationSeconds <= 0) return null

        // The IP captured via UserStats before the kick: non-empty pins the
        // exact entry (murmur stores the same bytes in BanEntry.address);
        // null/empty means "no IP knowledge" and identity matching applies.
        val address = pendingAddress?.takeIf { it.isNotEmpty() }

        // Strategy 1: the entry the server just appended = a reply entry that
        // is not part of the pre-ban snapshot. Comparing field-wise because
        // BanEntry's ByteArray address makes data-class equality reference-
        // based and useless here. Concurrent admins can append further entries
        // between the snapshot and the reply, so the diff alone is NOT enough:
        // the new candidates are filtered by the banned user's identity
        // (name/hash) too. A single candidate is exact; several candidates are
        // only distinguishable when the server-side entries differ, which is
        // the protocol ceiling (two identical entries for two same-named,
        // certificate-less users on the same IP are one and the same to
        // murmur — BanList carries no session to correlate against).
        if (previousBans != null) {
            val newCandidates = bans.indices.filter { i ->
                bans[i].duration == 0 && previousBans.none { bans[i].sameAs(it) }
            }
            val index = newCandidates.lastOrNull { i ->
                val ban = bans[i]
                namesMatch(ban.name, name) && hashesCompatible(ban.hash, hash) &&
                    addressMatches(ban.address, address)
            }
            if (index != null) {
                return bans.toMutableList().also { list ->
                    list[index] = list[index].copy(duration = durationSeconds)
                }
            }
            // Diff produced no identity-matching entry (list unchanged, the
            // new entry already carries a duration, or a concurrent admin
            // banned someone whose entry matches neither) — fall through to
            // the loose match rather than failing the whole patch.
        }

        // Strategy 2: legacy loose match on name/hash.
        val index = bans.indices.lastOrNull { i ->
            val ban = bans[i]
            ban.duration == 0 && namesMatch(ban.name, name) &&
                hashesCompatible(ban.hash, hash) && addressMatches(ban.address, address)
        } ?: return null
        return bans.toMutableList().also { list ->
            list[index] = list[index].copy(duration = durationSeconds)
        }
    }

    /**
     * When the banned user's IP is known, the entry must carry exactly that
     * address (murmur bans with mask 128 = the whole address). Entries with a
     * different address are other users, even same-named ones. Without IP
     * knowledge every address passes.
     */
    private fun addressMatches(banAddress: ByteArray, pendingAddress: ByteArray?): Boolean =
        pendingAddress == null || banAddress.contentEquals(pendingAddress)

    /** Content-based equality; data-class equals would compare ByteArray refs. */
    private fun BanEntry.sameAs(other: BanEntry): Boolean =
        duration == other.duration &&
            mask == other.mask &&
            name == other.name &&
            hash.equals(other.hash, ignoreCase = true) &&
            reason == other.reason &&
            start == other.start &&
            address.contentEquals(other.address)

    private fun namesMatch(banName: String, pendingName: String): Boolean =
        pendingName.isEmpty() || banName == pendingName

    private fun hashesCompatible(banHash: String, pendingHash: String): Boolean =
        pendingHash.isEmpty() ||
            banHash.isEmpty() ||
            banHash.equals(pendingHash, ignoreCase = true)
}
