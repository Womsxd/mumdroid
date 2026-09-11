package dev.woms.mumdroid.ui.screen

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanTimes
import java.time.Instant
import java.time.ZoneId

/**
 * The ban-length chips, grouped into short presets and long-term presets
 * (`longTerm`). Month/year presets need a start instant because a calendar
 * month is not a fixed number of seconds.
 */
internal enum class BanDurationPreset(
    val labelRes: Int,
    val longTerm: Boolean = false,
) {
    TEN_MINUTES(R.string.ban_preset_10m),
    ONE_HOUR(R.string.ban_preset_1h),
    TWELVE_HOURS(R.string.ban_preset_12h),
    ONE_DAY(R.string.ban_preset_1d),
    FIFTEEN_DAYS(R.string.ban_preset_15d),
    ONE_MONTH(R.string.ban_preset_1mo, longTerm = true),
    THREE_MONTHS(R.string.ban_preset_3mo, longTerm = true),
    SIX_MONTHS(R.string.ban_preset_6mo, longTerm = true),
    ONE_YEAR(R.string.ban_preset_1y, longTerm = true);

    fun secondsFrom(start: Instant): Int = when (this) {
        TEN_MINUTES -> 10 * 60
        ONE_HOUR -> 3_600
        TWELVE_HOURS -> 12 * 3_600
        ONE_DAY -> 86_400
        FIFTEEN_DAYS -> 15 * 86_400
        ONE_MONTH -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(1).toInstant(),
        )
        THREE_MONTHS -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(3).toInstant(),
        )
        SIX_MONTHS -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusMonths(6).toInstant(),
        )
        ONE_YEAR -> BanTimes.secondsBetween(
            start,
            start.atZone(ZoneId.systemDefault()).plusYears(1).toInstant(),
        )
    }
}

/** The days/hours/minutes text fields of a custom duration. */
internal fun durationFieldValues(seconds: Int): Triple<String, String, String> {
    val parts = BanTimes.partsFromSeconds(seconds)
    return Triple(parts.first.toString(), parts.second.toString(), parts.third.toString())
}

/** Default duration applied when the permanent switch is turned back off. */
internal const val DEFAULT_BAN_DURATION_SECONDS = 86_400
