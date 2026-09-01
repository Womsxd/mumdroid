package dev.woms.mumdroid.core.model

/**
 * Channel passwords are official access tokens (`#token` ACL groups).
 * Matching is case-insensitive (`Group::accessTokenCaseSensitivity`).
 */
object AccessTokens {
    fun normalize(token: String): String = token.trim()

    fun add(existing: List<String>, token: String): List<String> {
        val next = normalize(token)
        if (next.isEmpty()) return existing
        if (existing.any { it.equals(next, ignoreCase = true) }) return existing
        return existing + next
    }

    fun remove(existing: List<String>, token: String): List<String> {
        val target = normalize(token)
        if (target.isEmpty()) return existing
        return existing.filterNot { it.equals(target, ignoreCase = true) }
    }

    fun replace(existing: List<String>, old: String, new: String): List<String> {
        return add(remove(existing, old), new)
    }

    /**
     * Desktop `Tokens::accept`: trim, drop blanks, then keep first spelling of
     * each case-insensitive duplicate. Display order matches PC `sort()`.
     */
    fun sanitize(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw in tokens) {
            val next = normalize(raw)
            if (next.isEmpty()) continue
            if (out.any { it.equals(next, ignoreCase = true) }) continue
            out += next
        }
        return out.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}

/** Shown when a password-protected channel needs a token. */
data class ChannelPasswordPrompt(
    val channelId: Int,
    val channelName: String,
    val retry: Boolean = false,
)

/** Password parsed from a queried channel ACL (`#token` group). */
data class ChannelAclPassword(
    val channelId: Int,
    val password: String,
)
