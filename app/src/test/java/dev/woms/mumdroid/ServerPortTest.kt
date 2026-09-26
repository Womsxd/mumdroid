package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ServerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the server port field's rules. The dialog used to accept anything a
 * plain `toIntOrNull()` accepted, which stored 0 and 65536+ into the database.
 */
class ServerPortTest {

    @Test
    fun parse_acceptsTheUsableRange() {
        assertEquals(ServerPort.MIN, ServerPort.parse("1"))
        assertEquals(ServerPort.DEFAULT, ServerPort.parse("64738"))
        assertEquals(ServerPort.MAX, ServerPort.parse("65535"))
    }

    @Test
    fun parse_rejectsPortsASocketCannotUse() {
        assertNull(ServerPort.parse("0"))
        assertNull(ServerPort.parse("65536"))
        assertNull(ServerPort.parse("999999999"))
        assertNull(ServerPort.parse("-1"))
    }

    @Test
    fun parse_rejectsWhatIsNotANumber() {
        assertNull(ServerPort.parse(""))
        assertNull(ServerPort.parse("abc"))
        assertNull(ServerPort.parse("64 738"))
    }

    @Test
    fun parse_ignoresSurroundingWhitespace() {
        assertEquals(ServerPort.DEFAULT, ServerPort.parse(" 64738 "))
    }

    @Test
    fun filter_keepsDigitsOnlyAndStopsAtTheWidestPort() {
        assertEquals("64738", ServerPort.filter("64738"))
        assertEquals("64738", ServerPort.filter("-64a738"))
        assertEquals("65535", ServerPort.filter("655359999"))
        assertEquals("", ServerPort.filter("abc"))
    }

    /** A full-width digit parses as nothing, so the filter must not keep it. */
    @Test
    fun filter_dropsDigitsThatParseCannotRead() {
        assertEquals("", ServerPort.filter("１２３"))
    }
}
