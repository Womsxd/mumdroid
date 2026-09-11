package dev.woms.mumdroid

import dev.woms.mumdroid.ui.screen.BanDurationPreset
import dev.woms.mumdroid.ui.screen.DEFAULT_BAN_DURATION_SECONDS
import dev.woms.mumdroid.ui.screen.durationFieldValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The ban-length chips and the days/hours/minutes fields. Previously only
 * reachable through the composable, so the calendar-month arithmetic had no
 * coverage at all.
 */
class BanDurationPresetTest {

    private val start: Instant = ZonedDateTime
        .of(2024, 3, 31, 12, 0, 0, 0, ZoneId.systemDefault())
        .toInstant()

    @Test
    fun fixedPresets_areExactSecondCounts() {
        assertEquals(600, BanDurationPreset.TEN_MINUTES.secondsFrom(start))
        assertEquals(3_600, BanDurationPreset.ONE_HOUR.secondsFrom(start))
        assertEquals(12 * 3_600, BanDurationPreset.TWELVE_HOURS.secondsFrom(start))
        assertEquals(DEFAULT_BAN_DURATION_SECONDS, BanDurationPreset.ONE_DAY.secondsFrom(start))
        assertEquals(15 * 86_400, BanDurationPreset.FIFTEEN_DAYS.secondsFrom(start))
    }

    @Test
    fun calendarPresets_trackTheLocalCalendar() {
        // A calendar month is not a fixed second count, which is why these
        // presets take the start instant at all.
        val end = ZonedDateTime.ofInstant(start, ZoneId.systemDefault())
            .plusMonths(1)
            .toInstant()
        assertEquals(
            (end.epochSecond - start.epochSecond).toInt(),
            BanDurationPreset.ONE_MONTH.secondsFrom(start),
        )
        val yearEnd = ZonedDateTime.ofInstant(start, ZoneId.systemDefault())
            .plusYears(1)
            .toInstant()
        assertEquals(
            (yearEnd.epochSecond - start.epochSecond).toInt(),
            BanDurationPreset.ONE_YEAR.secondsFrom(start),
        )
    }

    @Test
    fun calendarPresets_growMonotonically() {
        val month = BanDurationPreset.ONE_MONTH.secondsFrom(start).toLong()
        val quarter = BanDurationPreset.THREE_MONTHS.secondsFrom(start).toLong()
        val half = BanDurationPreset.SIX_MONTHS.secondsFrom(start).toLong()
        val year = BanDurationPreset.ONE_YEAR.secondsFrom(start).toLong()
        assertTrue(month < quarter)
        assertTrue(quarter < half)
        assertTrue(half < year)
        // Sanity: a calendar month is between 28 and 31 days.
        assertTrue(month in 28L * 86_400..31L * 86_400)
    }

    @Test
    fun presets_splitIntoShortAndLongTermGroups() {
        assertEquals(
            listOf(
                BanDurationPreset.TEN_MINUTES,
                BanDurationPreset.ONE_HOUR,
                BanDurationPreset.TWELVE_HOURS,
                BanDurationPreset.ONE_DAY,
                BanDurationPreset.FIFTEEN_DAYS,
            ),
            BanDurationPreset.entries.filter { !it.longTerm },
        )
        assertEquals(
            listOf(
                BanDurationPreset.ONE_MONTH,
                BanDurationPreset.THREE_MONTHS,
                BanDurationPreset.SIX_MONTHS,
                BanDurationPreset.ONE_YEAR,
            ),
            BanDurationPreset.entries.filter { it.longTerm },
        )
        // Both rows render every preset exactly once.
        assertEquals(
            BanDurationPreset.entries.toList(),
            BanDurationPreset.entries.filter { !it.longTerm } +
                BanDurationPreset.entries.filter { it.longTerm },
        )
    }

    @Test
    fun everyPresetHasItsOwnLabel() {
        assertEquals(
            BanDurationPreset.entries.size,
            BanDurationPreset.entries.map { it.labelRes }.distinct().size,
        )
    }

    @Test
    fun durationFieldValues_splitSecondsIntoFields() {
        assertEquals(Triple("1", "2", "3"), durationFieldValues(86_400 + 7_200 + 180))
        assertEquals(Triple("0", "0", "10"), durationFieldValues(600))
        assertEquals(Triple("0", "0", "0"), durationFieldValues(0))
    }
}
