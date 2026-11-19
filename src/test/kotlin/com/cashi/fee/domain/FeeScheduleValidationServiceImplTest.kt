package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * Startup validation: a malformed schedule fails while it is built, before it can price anything.
 * A rule checks itself when it is built. [FeeScheduleValidationServiceImpl] checks what needs the
 * asset registry or the whole schedule.
 */
class FeeScheduleValidationServiceImplTest {

    private val validation = FeeScheduleValidationServiceImpl(DefaultAssetRegistry())

    private fun schedule(vararg types: TransactionType): FeeSchedule = object : FeeSchedule {
        override val types = types.toList()
    }

    private fun type(label: String, rule: FeeRule, allowed: Set<AssetType> = setOf(AssetType.FIAT)) =
        TransactionType(label, rule, allowed, label.lowercase().replace(' ', '-'), 1)

    private fun fixed(vararg fees: Pair<String, String>) = FeeRule.Fixed(fees.associate { (code, fee) -> code to BigDecimal(fee) })

    @Test
    fun `the shipped schedule is valid`() {
        val schedule = DefaultFeeSchedule()

        assertEquals(emptyList<String>(), validation.problems(schedule))
        assertSame(schedule, validation.requireValid(schedule))
    }

    @Test
    fun `the shipped schedule holds exactly the AC14 types`() {
        val types = DefaultFeeSchedule().types

        assertEquals(listOf("Mobile Top Up", "Bank Transfer", "Charity Donation"), types.map { it.label })
        assertEquals(
            listOf(
                TransactionType("Mobile Top Up", FeeRule.Percentage(BigDecimal("0.0015")), setOf(AssetType.FIAT, AssetType.CRYPTO), "mobile-top-up", 1),
                TransactionType("Bank Transfer", fixed("USD" to "0.50", "EUR" to "0.50", "GBP" to "0.50", "JPY" to "75"), setOf(AssetType.FIAT), "bank-transfer", 1),
                TransactionType("Charity Donation", FeeRule.NoFee("No fee for charity donations"), setOf(AssetType.FIAT, AssetType.CRYPTO), "charity-donation", 1),
            ),
            types,
        )
    }

    @Test
    fun `a label registered twice fails`() {
        val failure = assertThrows<IllegalArgumentException> {
            validation.requireValid(
                schedule(type("Bank Transfer", fixed("USD" to "0.50")), type("Bank Transfer", FeeRule.NoFee("free"))),
            )
        }

        assertTrue("\"Bank Transfer\" is registered more than once" in failure.message!!) { failure.message }
    }

    @Test
    fun `a fixed fee with more decimals than its asset allows fails`() {
        val failure = assertThrows<IllegalArgumentException> {
            validation.requireValid(schedule(type("Bank Transfer", fixed("USD" to "0.505", "JPY" to "0.5"))))
        }

        assertTrue("fixed fee of 0.505 USD has more decimals than USD allows (2)" in failure.message!!) { failure.message }
        assertTrue("fixed fee of 0.5 JPY has more decimals than JPY allows (0)" in failure.message!!) { failure.message }
    }

    /**
     * A fee is charged as Money in its asset, so a fixed fee past Money's integer digits could
     * never be quoted.
     */
    @Test
    fun `a fixed fee with more integer digits than money holds fails`() {
        val failure = assertThrows<IllegalArgumentException> {
            validation.requireValid(schedule(type("Bank Transfer", fixed("USD" to "100000000000000000000", "JPY" to "99999999999999999999"))))
        }

        assertTrue("fixed fee of 100000000000000000000 USD must have at most 20 integer digits" in failure.message!!) { failure.message }
        assertTrue("JPY" !in failure.message!!) { failure.message }
    }

    @Test
    fun `trailing zeros on a fixed fee are not decimals`() {
        assertEquals(emptyList<String>(), validation.problems(schedule(type("Bank Transfer", fixed("USD" to "0.500", "JPY" to "75.0")))))
    }

    @Test
    fun `a fixed fee for an asset the registry doesn't hold fails`() {
        val failure = assertThrows<IllegalArgumentException> {
            validation.requireValid(schedule(type("Bank Transfer", fixed("usd" to "0.50"))))
        }

        assertTrue("fixed fee for usd, which is not a registered asset" in failure.message!!) { failure.message }
    }

    @Test
    fun `every problem is listed at once`() {
        val problems = validation.problems(
            schedule(
                type("Bank Transfer", fixed("USD" to "0.505")),
                type("Bank Transfer", fixed("CHF" to "0.50")),
            ),
        )

        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun `a negative fixed fee fails while the schedule is built`() {
        val failure = assertThrows<IllegalArgumentException> { schedule(type("Bank Transfer", fixed("USD" to "0.50", "EUR" to "-0.50"))) }

        assertEquals("the fixed fee for EUR must not be negative, was -0.50", failure.message)
    }

    @Test
    fun `a fixed fee with no asset at all fails while the schedule is built`() {
        assertThrows<IllegalArgumentException> { schedule(type("Bank Transfer", fixed())) }
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(strings = ["1", "1.5", "-0.0001"])
    fun `a percentage outside 0 to 1 fails while the schedule is built`(rate: String) {
        assertThrows<IllegalArgumentException> { schedule(type("Mobile Top Up", FeeRule.Percentage(BigDecimal(rate)))) }
    }

    @Test
    fun `a percentage of 0 is allowed`() {
        assertEquals(emptyList<String>(), validation.problems(schedule(type("Promo", FeeRule.Percentage(BigDecimal.ZERO)))))
    }

    @ParameterizedTest(name = "description \"{0}\"")
    @ValueSource(strings = ["", "   "])
    fun `an empty description fails while the schedule is built`(description: String) {
        assertThrows<IllegalArgumentException> { schedule(type("Charity Donation", FeeRule.NoFee(description))) }
    }
}
