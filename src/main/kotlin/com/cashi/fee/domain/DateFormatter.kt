package com.cashi.fee.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

/**
 * The one accepted `created_at` format (D8, AC6): `yyyy-MM-dd HH:mm:ss.SSSSSS`, a space separator,
 * exactly six fractional digits, no zone, resolved strictly. The value is a UTC local date-time.
 * A value helper, so an `object` (Q15).
 *
 * The year is exactly four unsigned digits, 0001 to 9999. A `uuuu` pattern would also accept a sign
 * and more digits (`+294277`, `-5000`), which `fee_record.created_at timestamp(6)` can't store: the
 * fee would be charged and its record would then fail on every retry (or be stored as `-infinity`).
 */
object DateFormatter {

    private const val MIN_YEAR = 1
    private const val MAX_YEAR = 9999

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendLiteral('.')
            .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, false)
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT)

    /** The parsed value, or null when [text] is in any other format, is not a real date-time, or is year 0000. */
    fun parse(text: String): LocalDateTime? =
        try {
            LocalDateTime.parse(text, FORMAT).takeIf { it.year >= MIN_YEAR }
        } catch (_: DateTimeParseException) {
            null
        }

    /** The canonical text form, the one [parse] accepts. Throws for a year outside 0001–9999: see [formatOrNull]. */
    fun format(value: LocalDateTime): String = FORMAT.format(value)

    /**
     * The canonical text form, or null for a year outside 0001–9999, which this format can't write.
     * For input that hasn't been checked yet (a direct workflow run), so a bad year becomes a
     * rejection rather than an exception.
     */
    fun formatOrNull(value: LocalDateTime): String? = if (value.year in MIN_YEAR..MAX_YEAR) format(value) else null
}
