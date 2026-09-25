package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.model.MumbleVersion.formatLegacyVersion
import dev.woms.mumdroid.core.model.MumbleVersion.legacyToV2
import dev.woms.mumdroid.core.model.MumbleVersion.resolveV2


/**
 * Packed Mumble protocol versions, matching official `Version::toString`.
 *
 * Lives in model (no proto / net) so session snapshots can format a version
 * without depending on the ping codec — the direction needed if the wire
 * library is split out later.
 */
object MumbleVersion {

    /**
     * Official `Version::fromComponents(1, 5, 0)` in the v2 packing: the version
     * from which the server understands protobuf tunneled packets.
     *
     * A protocol threshold, not the version this client reports. The two are
     * both 1.5.0 today but say different things, so they are deliberately not
     * one constant. Compare it against the result of [resolveV2].
     */
    val PROTOBUF_INTRODUCTION_VERSION_V2: Long = (1L shl 48) or (5L shl 32)

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

    /**
     * Re-packs a legacy version into the v2 packing, the inverse of
     * [formatLegacyVersion]. The two packings share the component split
     * (`major`/`minor`/`patch`), so this only re-lays out the same three
     * values.
     *
     * Servers older than 1.5 report only the legacy form, so any comparison
     * against a `Version::fromComponents` constant has to go through here
     * first; see [resolveV2].
     */
    fun legacyToV2(legacy: Int): Long {
        val major = (legacy ushr 16) and 0xffff
        val minor = (legacy ushr 8) and 0xff
        val patch = legacy and 0xff
        return (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    }

    /**
     * The v2 version to compare against: [versionV2] when the server reported
     * one, otherwise [legacyVersion] re-packed by [legacyToV2].
     *
     * 0 means "absent" in both inputs, and a version that was not reported at
     * all stays 0, i.e. below every real version.
     */
    fun resolveV2(versionV2: Long, legacyVersion: Int): Long =
        if (versionV2 != 0L) versionV2 else legacyToV2(legacyVersion)
}
