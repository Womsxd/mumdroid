package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.BanAddresses
import dev.woms.mumdroid.core.model.BanIpKind
import dev.woms.mumdroid.core.model.BanTimes
import dev.woms.mumdroid.core.model.TimedUserBan
import dev.woms.mumdroid.core.net.BanEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BanAddressesTest {

    @Test
    fun encodeIpv4_usesMappedPrefixAndAdds96ToMask() {
        val encoded = BanAddresses.encode("192.168.1.10", 32)!!
        assertEquals(16, encoded.first.size)
        assertEquals(128, encoded.second)
        assertEquals("192.168.1.10", BanAddresses.displayAddress(encoded.first))
        assertEquals(32, BanAddresses.displayMask(encoded.first, encoded.second))
    }

    @Test
    fun encodeEmpty_isHashOnlyBan() {
        val encoded = BanAddresses.encode("", 32)!!
        assertEquals(0, encoded.first.size)
        assertEquals(0, encoded.second)
        assertNull(BanAddresses.encode("not-an-ip", 32))
        assertNull(BanAddresses.encode("example.com", 32))
    }

    @Test
    fun parseKind_onlyLiteralAddresses() {
        assertEquals(BanIpKind.V4, BanAddresses.parseKind("10.0.0.1"))
        assertEquals(BanIpKind.V6, BanAddresses.parseKind("2001:db8::1"))
        assertEquals(BanIpKind.V4, BanAddresses.parseKind("::ffff:192.168.0.1"))
        assertNull(BanAddresses.parseKind("example.com"))
        assertNull(BanAddresses.parseKind("1.2.3"))
        assertNull(BanAddresses.parseKind("999.1.1.1"))
    }

    @Test
    fun maskRange_matchesProtocol() {
        assertEquals(8..32, BanAddresses.maskRange(BanIpKind.V4))
        assertEquals(8..128, BanAddresses.maskRange(BanIpKind.V6))
    }

    @Test
    fun label_prefersNameThenIpThenHash() {
        val encoded = BanAddresses.encode("10.0.0.1", 32)!!
        val ip = encoded.first
        assertEquals("alice", BanAddresses.label("alice", ip, "abc", encoded.second))
        assertEquals("10.0.0.1/32", BanAddresses.label("", ip, "abc", encoded.second))
        assertEquals("10.0.0.1", BanAddresses.label("", ip, "abc"))
        assertEquals("abc", BanAddresses.label("", byteArrayOf(), "abc"))
    }

    @Test
    fun canSave_newBanRequiresIpOnly() {
        assertEquals(
            true,
            BanAddresses.canSave(
                address = "10.0.0.1",
                mask = 32,
                hash = "",
                permanent = true,
                durationSeconds = 0,
                requireIp = true,
            ),
        )
        assertEquals(
            false,
            BanAddresses.canSave(
                address = "",
                mask = 32,
                hash = "abc",
                permanent = true,
                durationSeconds = 0,
                requireIp = true,
            ),
        )
        assertEquals(
            true,
            BanAddresses.canSave(
                address = "",
                mask = 32,
                hash = "abc",
                permanent = true,
                durationSeconds = 0,
                requireIp = false,
            ),
        )
    }
}

class TimedUserBanTest {

    @Test
    fun applyDuration_patchesNewestMatchingPermanentBan() {
        val older = BanEntry(name = "bob", hash = "h", reason = "x", duration = 0)
        val newest = BanEntry(name = "bob", hash = "h", reason = "spam", duration = 0)
        val other = BanEntry(name = "alice", hash = "a", reason = "spam", duration = 0)
        val patched = TimedUserBan.applyDuration(
            listOf(older, other, newest),
            name = "bob",
            hash = "h",
            durationSeconds = 600,
        )!!
        assertEquals(0, patched[0].duration)
        assertEquals(0, patched[1].duration)
        assertEquals(600, patched[2].duration)
    }

    @Test
    fun applyDuration_matchesIpOnlyBanWhenPendingHasCertHash() {
        val ban = BanEntry(name = "bob", hash = "", reason = "spam", duration = 0)
        val patched = TimedUserBan.applyDuration(
            listOf(ban),
            name = "bob",
            hash = "abc",
            durationSeconds = 86_400,
        )!!
        assertEquals(86_400, patched[0].duration)
    }

    @Test
    fun applyDuration_matchesHashIgnoringCase() {
        val ban = BanEntry(name = "bob", hash = "AbC", duration = 0)
        val patched = TimedUserBan.applyDuration(
            listOf(ban),
            name = "bob",
            hash = "abc",
            durationSeconds = 60,
        )!!
        assertEquals(60, patched[0].duration)
    }
}

class BanTimesTest {

    @Test
    fun partsFromSeconds_splitsDaysHoursMinutes() {
        assertEquals(Triple(1, 2, 3), BanTimes.partsFromSeconds(86_400 + 7_200 + 180))
        assertEquals(Triple(0, 0, 10), BanTimes.partsFromSeconds(600))
    }

    @Test
    fun secondsBetween_clampsNonPositiveToZero() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val earlier = Instant.parse("2025-12-31T00:00:00Z")
        assertEquals(0, BanTimes.secondsBetween(start, earlier))
        assertEquals(3_600, BanTimes.secondsBetween(start, start.plusSeconds(3_600)))
    }

    @Test
    fun isEffectivelyExpired_usesFiveSecondGrace() {
        val now = Instant.parse("2026-08-30T04:00:00Z")
        assertEquals(false, BanTimes.isEffectivelyExpired(now, 0, now))
        assertEquals(false, BanTimes.isEffectivelyExpired(now, 60, now))
        assertEquals(true, BanTimes.isEffectivelyExpired(now, 5, now))
        assertEquals(true, BanTimes.isEffectivelyExpired(now.minusSeconds(3_600), 3_600, now))
        assertEquals(false, BanTimes.isEffectivelyExpired(now.minusSeconds(3_600), 3_606, now))
    }
}
