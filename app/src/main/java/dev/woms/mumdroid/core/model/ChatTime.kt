package dev.woms.mumdroid.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Chat-log time labels matching desktop Mumble (`Log.cpp`: `[HH:mm:ss]` or
 * `[hh:mm:ss AP]`, plus a date-changed marker when the local day rolls over).
 */
object ChatTime {
    fun formatTime(
        timestampMs: Long,
        use24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalTime()
        val pattern = if (use24Hour) "HH:mm:ss" else "hh:mm:ss a"
        return "[${time.format(DateTimeFormatter.ofPattern(pattern, locale))}]"
    }

    fun formatDate(
        timestampMs: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = localDate(timestampMs, zone)
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }

    fun sameLocalDate(
        firstMs: Long,
        secondMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = localDate(firstMs, zone) == localDate(secondMs, zone)

    fun localDate(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
}
