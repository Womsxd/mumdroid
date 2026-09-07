package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class ChatTimeTest {

    private val afternoon = Instant.parse("2026-09-07T13:05:09Z").toEpochMilli()
    private val nextDay = Instant.parse("2026-09-08T00:00:01Z").toEpochMilli()

    @Test
    fun formats24HourTime() {
        assertEquals(
            "[13:05:09]",
            ChatTime.formatTime(afternoon, use24Hour = true, zone = ZoneOffset.UTC),
        )
    }

    @Test
    fun formats12HourTime() {
        assertEquals(
            "[01:05:09 PM]",
            ChatTime.formatTime(
                afternoon,
                use24Hour = false,
                zone = ZoneOffset.UTC,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun detectsDateChange() {
        assertTrue(ChatTime.sameLocalDate(afternoon, afternoon, ZoneOffset.UTC))
        assertFalse(ChatTime.sameLocalDate(afternoon, nextDay, ZoneOffset.UTC))
    }
}
