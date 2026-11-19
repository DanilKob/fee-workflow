package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

/** The date parser: one strict format, a UTC local date-time with microseconds. */
class DateFormatterTest {

    @Test
    fun `parses the brief's format to the exact microsecond`() {
        assertEquals(LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000), DateFormatter.parse("2023-08-30 15:42:17.610059"))
    }

    @Test
    fun `formats back exactly what it parsed`() {
        val text = "2023-08-30 15:42:17.610059"
        assertEquals(text, DateFormatter.format(DateFormatter.parse(text)!!))
        assertEquals("2023-08-30 15:42:17.000000", DateFormatter.format(LocalDateTime.of(2023, 8, 30, 15, 42, 17)))
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(
        strings = [
            "2023-08-30T15:42:17.610059Z", // ISO with zone
            "2023-08-30T15:42:17.610059", // T separator
            "2023-08-30 15:42:17", // no fraction
            "2023-08-30 15:42:17.610", // three fractional digits
            "2023-08-30 15:42:17.6100590", // seven fractional digits
            "2023-08-30 25:00:00.000000", // no hour 25
            "2023-02-30 10:00:00.000000", // no 30 February (strict)
            "2023-8-30 15:42:17.610059", // unpadded month
            " 2023-08-30 15:42:17.610059", // leading space
            "",
            "+294277-01-01 00:00:00.000000", // signed year past timestamp(6)'s range
            "-5000-01-01 00:00:00.000000", // negative year (would be stored as -infinity)
            "+2023-08-30 15:42:17.610059", // a sign on a four-digit year
            "12345-01-01 00:00:00.000000", // five-digit year
            "0000-01-01 00:00:00.000000", // year 0000
        ],
    )
    fun `rejects every other format`(text: String) {
        assertNull(DateFormatter.parse(text))
    }

    @Test
    fun `formatOrNull writes a year the format can hold and returns null for any other, never throwing`() {
        assertEquals("2023-08-30 15:42:17.610059", DateFormatter.formatOrNull(LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000)))
        assertNull(DateFormatter.formatOrNull(LocalDateTime.of(12345, 1, 1, 0, 0)))
        assertNull(DateFormatter.formatOrNull(LocalDateTime.of(-5000, 1, 1, 0, 0)))
        assertNull(DateFormatter.formatOrNull(LocalDateTime.of(0, 1, 1, 0, 0)))
    }

    @ParameterizedTest(name = "accepts \"{0}\"")
    @ValueSource(strings = ["0001-01-01 00:00:00.000000", "9999-12-31 23:59:59.999999", "2024-02-29 12:00:00.000001"])
    fun `accepts the whole four-digit year range, round-tripping exactly`(text: String) {
        assertEquals(text, DateFormatter.format(DateFormatter.parse(text)!!))
    }
}
