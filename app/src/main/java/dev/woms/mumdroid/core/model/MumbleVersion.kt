package dev.woms.mumdroid.core.model

/**
 * Packed Mumble protocol versions, matching official `Version::toString`.
 *
 * Lives in model (no proto / net) so session snapshots can format a version
 * without depending on the ping codec — the direction needed if the wire
 * library is split out later.
 */
object MumbleVersion {

    /**
     * Legacy packed version (`major<<16 | minor<<8 | patch`, 16.8.8).
     * Mirrors `Version::fromLegacyVersion` + `Version::toString`.
     */
    fun formatLegacyVersion(packed: Int): String? {
        if (packed == 0) return null
        val major = (packed ushr 16) and 0xffff
        val minor = (packed ushr 8) and 0xff
        val patch = packed and 0xff
        return "$major.$minor.$patch"
    }

    /**
     * v2 version (`major<<48 | minor<<32 | patch<<16`).
     * Mirrors `Version::toString` for `full_t`.
     */
    fun formatVersionV2(version: Long): String? {
        if (version == 0L) return null
        val major = (version ushr 48) and 0xffff
        val minor = (version ushr 32) and 0xffff
        val patch = (version ushr 16) and 0xffff
        return "$major.$minor.$patch"
    }
}
