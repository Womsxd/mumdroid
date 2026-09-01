package dev.woms.mumdroid

import dev.woms.mumdroid.data.SettingsStore.LegacyLastChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyLastChannelTest {

    @Test
    fun parsesHostPortIdAndName() {
        val parsed = LegacyLastChannel.parse("voice.example.com:64738\t7\tGames")
        assertEquals("voice.example.com", parsed?.host)
        assertEquals(64738, parsed?.port)
        assertEquals(7, parsed?.id)
        assertEquals("Games", parsed?.name)
    }

    @Test
    fun keepsTabInsideChannelName() {
        val parsed = LegacyLastChannel.parse("host:1\t3\tFoo\tBar")
        assertEquals("Foo\tBar", parsed?.name)
    }

    @Test
    fun missingName_isEmpty() {
        val parsed = LegacyLastChannel.parse("host:64738\t0")
        assertEquals(0, parsed?.id)
        assertEquals("", parsed?.name)
    }

    @Test
    fun ipv6Host_usesLastColonAsPort() {
        val parsed = LegacyLastChannel.parse("2001:db8::1:64738\t4\tLobby")
        assertEquals("2001:db8::1", parsed?.host)
        assertEquals(64738, parsed?.port)
        assertEquals(4, parsed?.id)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(LegacyLastChannel.parse(""))
        assertNull(LegacyLastChannel.parse("noport\t1"))
        assertNull(LegacyLastChannel.parse(":64738\t1"))
        assertNull(LegacyLastChannel.parse("host:x\t1"))
        assertNull(LegacyLastChannel.parse("host:64738\tx"))
    }
}
