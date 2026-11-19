package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Duration

/**
 * The money value object: its invariants, its plain number rendering
 * (`stripTrailingZeros().toPlainString()`), the one rounding, and bound checks that never expand
 * an exponent.
 */
class MoneyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "1000, 1000",
        "1000.00, 1000", // the same number, rendered plain
        "1E+3, 1000", // never 1E+3
        "1.50, 1.5", // never 1.50
        "0.0015, 0.0015", // never 1.5E-3
        "1.5E-3, 0.0015",
        "0.00, 0",
        "12345678901234567.89, 12345678901234567.89",
    )
    fun `renders plain, without trailing zeros or exponent`(value: String, rendered: String) {
        assertEquals(rendered, Money.plain(BigDecimal(value)))
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource("1000.00, USD, 1000", "1.50, USD, 1.5", "0.00000001, BTC, 0.00000001", "1E+3, JPY, 1000")
    fun `an amount renders itself plain, by the same rule`(value: String, asset: String, rendered: String) {
        assertEquals(rendered, Money(BigDecimal(value), asset(asset)).plain())
    }

    @Test
    fun `an amount keeps the value it was given, scale included`() {
        assertEquals(BigDecimal("1000.00"), Money(BigDecimal("1000.00"), USD).amount)
        assertEquals(2, Money(BigDecimal("1000.00"), USD).amount.scale())
    }

    @ParameterizedTest(name = "{0} has {1} integer digits")
    @CsvSource(
        "1000, 4",
        "1000.00, 4",
        "0.5, 0",
        "99999999999999999999.99, 20",
        "100000000000000000000, 21",
        "1E+30, 31",
        "1E+20000000, 20000001", // read from precision and scale, never expanded
        "1E+2147483647, 2147483648", // never overflows into a small number
        "1E-500000000, -499999999",
    )
    fun `counts integer digits without rendering`(value: String, digits: Long) {
        assertEquals(digits, Money.integerDigits(BigDecimal(value)))
    }

    @ParameterizedTest(name = "{0} has {1} significant decimals")
    @CsvSource("10.000, -1", "10.50, 1", "10.001, 3", "1.50, 1", "1E+3, -3")
    fun `counts significant decimals only (Q13)`(value: String, scale: Int) {
        assertEquals(scale, Money.significantScale(BigDecimal(value)))
    }

    @ParameterizedTest(name = "{0} has more than {1} decimals: {2}")
    @CsvSource(
        "10.00, 2, false",
        "10.000, 2, false", // trailing zeros don't count
        "10.001, 2, true",
        "0.01, 2, false",
        "0.001, 2, true", // the first significant digit is past the scale
        "0.0010, 2, true",
        "0.0100, 2, false",
        "0E-500000000, 2, false", // zero has no significant decimals
        "1E-18, 18, false",
        "1E-19, 18, true",
        "1E-500000000, 18, true", // decided from precision and scale, never expanded
        "1E-2147483647, 18, true",
    )
    fun `tells whether an amount has more significant decimals than a scale`(value: String, maxScale: Int, more: Boolean) {
        val amount = BigDecimal(value)

        assertEquals(more, assertTimeoutPreemptively(Duration.ofMillis(100)) { Money.hasMoreDecimalsThan(amount, maxScale) })
    }

    /**
     * The invariants in an asset, read from precision and scale: `1E-500000000` and `1E+2147483647`
     * are refused at once. Pricing `1E-500000000` would compute 10^500000000 in `setScale`.
     */
    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "100000000000000000000 | USD | must have at most 20 integer digits",
            "1E+20000000           | USD | must have at most 20 integer digits",
            "1E+2147483647         | USD | must have at most 20 integer digits",
            "10.001                | USD | has more decimals than USD allows (2)",
            "1E-30                 | USD | has more decimals than USD allows (2)",
            "1E-500000000          | USD | has more decimals than USD allows (2)",
            "1E-2147483647         | ETH | has more decimals than ETH allows (18)",
            "1000.5                | JPY | has more decimals than JPY allows (0)",
            "0.123456789           | BTC | has more decimals than BTC allows (8)",
            "1.0000001             | USDC| has more decimals than USDC allows (6)",
            "1E-19                 | ETH | has more decimals than ETH allows (18)",
        ],
    )
    fun `an amount outside its asset's bounds is refused at once, by check and by the constructor`(value: String, asset: String, message: String) {
        val amount = BigDecimal(value)
        val inAsset = asset(asset)

        val violation = assertTimeoutPreemptively(Duration.ofMillis(100)) { Money.check(amount, inAsset) }
        val failure = assertTimeoutPreemptively(Duration.ofMillis(100)) { assertThrows<IllegalArgumentException> { Money(amount, inAsset) } }

        assertEquals(RuleViolation(TransactionField.AMOUNT, message), violation)
        assertTrue(failure.message.orEmpty().endsWith(message)) { failure.message }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "99999999999999999999.99, USD",
        "10.000, USD", // trailing zeros don't count
        "1000.0, JPY",
        "0.12345678, BTC",
        "1.234567890123456789, ETH",
        "0, USD", // a fee that rounds to zero is still money
    )
    fun `an amount within its asset's bounds is money`(value: String, asset: String) {
        assertNull(Money.check(BigDecimal(value), asset(asset)))
        assertEquals(BigDecimal(value), Money(BigDecimal(value), asset(asset)).amount)
    }

    /** For an amount whose asset isn't known: the integer digits, then the largest scale any asset has. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "100000000000000000000 | must have at most 20 integer digits",
            "1E+2147483647         | must have at most 20 integer digits",
            "1E-30                 | must have at most 18 decimal places",
            "1e-500000000          | must have at most 18 decimal places",
            "1.0000000000000000001 | must have at most 18 decimal places",
        ],
    )
    fun `the bounds for any asset are checked at once`(value: String, message: String) {
        val amount = BigDecimal(value)

        assertEquals(RuleViolation(TransactionField.AMOUNT, message), assertTimeoutPreemptively(Duration.ofMillis(100)) { Money.checkBounds(amount) })
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("1000", "0.000000000000000001", "99999999999999999999.99", "-1", "0")
    fun `an amount within the bounds for any asset passes them, whatever its sign`(value: String) {
        assertNull(Money.checkBounds(BigDecimal(value)))
    }

    /**
     * Rounded once, half-up, at the asset's scale.
     * These are the cases where half-even or a Double would give another fee.
     */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "0.015, USD, 0.02",
        "0.495, USD, 0.50",
        "0.045, EUR, 0.05", // HALF_EVEN gives 0.04
        "4.5, JPY, 5", // HALF_EVEN gives 4
        "0.000000045, BTC, 0.00000005",
        "18518518351851.851835, USD, 18518518351851.85",
        "0, USD, 0.00",
    )
    fun `a raw fee is rounded once, HALF_UP, at its asset's scale`(raw: String, asset: String, fee: String) {
        val rounded = Money.rounded(BigDecimal(raw), asset(asset))

        assertEquals(Money(BigDecimal(fee), asset(asset)), rounded)
        assertEquals(asset(asset).scale, rounded.amount.scale())
    }

    @Test
    fun `amounts of one asset compare numerically, and amounts of two assets don't compare`() {
        assertTrue(Money(BigDecimal("0.51"), USD) > Money(BigDecimal("0.50"), USD))
        assertEquals(0, Money(BigDecimal("1000.00"), USD).compareTo(Money(BigDecimal("1E+3"), USD)))
        assertTrue(Money(BigDecimal("0.5"), USD) <= Money(BigDecimal("0.50"), USD))
        assertThrows<IllegalArgumentException> { Money(BigDecimal("1"), USD).compareTo(Money(BigDecimal("1"), asset("EUR"))) }
    }

    @Test
    fun `the bounds match numeric(38,18)`() {
        assertEquals(38, Money.MAX_INTEGER_DIGITS + Money.MAX_SCALE)
    }

    private fun asset(code: String): Asset = checkNotNull(ASSETS.find(code)) { "$code is not registered" }

    private companion object {
        val ASSETS = DefaultAssetRegistry()
        val USD = ASSETS.find("USD")!!
    }
}
