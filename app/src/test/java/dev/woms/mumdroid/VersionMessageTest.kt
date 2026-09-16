package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.proto.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Version` handshake message: the only place the client tells the server
 * anything about itself.
 *
 * With the client info hidden, `os` / `os_version` are omitted — not blanked —
 * and `release` carries the protocol version instead of the client name, so the
 * checks are on field presence and on the reported release.
 */
class VersionMessageTest {

    private val release = "mumdroid 1.1.2-abc1234"
    private val osVersion = "15"

    private fun build(hideClientInfo: Boolean): Version = MumbleClient.buildVersion(
        hideClientInfo = hideClientInfo,
        release = release,
        osVersion = osVersion,
    )

    @Test
    fun identityIsSentByDefault() {
        val version = build(hideClientInfo = false)

        assertTrue(version.hasOs())
        assertTrue(version.hasOsVersion())
        assertEquals("Android", version.os)
        assertEquals(osVersion, version.osVersion)
        assertEquals(release, version.release)
    }

    @Test
    fun hidingLeavesTheOsFieldsOutEntirely() {
        val version = build(hideClientInfo = true)

        assertFalse(version.hasOs())
        assertFalse(version.hasOsVersion())
        assertEquals("", version.os)
        assertEquals("", version.osVersion)
    }

    @Test
    fun hidingReportsTheProtocolVersionAsTheRelease() {
        // No app name, version name or git hash: the string says no more than
        // version_v1 / version_v2 already do.
        assertEquals("1.5.0", build(hideClientInfo = true).release)
    }

    @Test
    fun versionNumbersSurviveEitherWay() {
        // The server picks the UDP framing from these, so neither mode may touch
        // them — and the hidden release is derived from them.
        for (version in listOf(build(false), build(true))) {
            assertEquals(0x010500, version.versionV1)
            assertEquals(0x0001000500000000L, version.versionV2)
        }
    }
}
