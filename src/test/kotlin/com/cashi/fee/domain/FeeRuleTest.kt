package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** Each fee kind computes, rates and describes itself. Rounding is the calculator's job. */
class FeeRuleTest {

    private val usd = Asset("USD", AssetType.FIAT, 2)
    private val jpy = Asset("JPY", AssetType.FIAT, 0)
    private val btc = Asset("BTC", AssetType.CRYPTO, 8)

    @Test
    fun `a percentage is a share of the amount, unrounded, with its rate and the standard description`() {
        val rule = FeeRule.Percentage(BigDecimal("0.0015"))

        assertEquals(BigDecimal("0.0150"), rule.compute(BigDecimal("10"), usd))
        assertEquals(BigDecimal("0.0015"), rule.nominalRate())
        assertEquals("Standard fee rate of 0.15%", rule.describe(usd))
    }

    @Test
    fun `a percentage rate is refused past the 18 decimals fee_record can store, trailing zeros not counted`() {
        assertThrows<IllegalArgumentException> { FeeRule.Percentage(BigDecimal("0.0000000000000000015")) }
        FeeRule.Percentage(BigDecimal("0.000000000000000001"))
        FeeRule.Percentage(BigDecimal("0.0015000000000000000000"))
    }

    @Test
    fun `a fixed fee is the asset's own fee whatever the amount, with rate 0, described at the asset's scale`() {
        val rule = FeeRule.Fixed(mapOf("USD" to BigDecimal("0.5"), "JPY" to BigDecimal("75")))

        assertEquals(0, BigDecimal("0.50").compareTo(rule.compute(BigDecimal("0.01"), usd)))
        assertEquals(0, BigDecimal("0.50").compareTo(rule.compute(BigDecimal("99999999999999999999.99"), usd)))
        assertEquals(BigDecimal("75"), rule.compute(BigDecimal("1000"), jpy))
        assertEquals(0, rule.nominalRate().signum())
        // "0.5" configured, "0.50" described: the fee is written at its asset's scale.
        assertEquals("Flat fee of 0.50 USD", rule.describe(usd))
        assertEquals("Flat fee of 75 JPY", rule.describe(jpy))
    }

    @Test
    fun `a fixed fee knows which assets it can price, and refuses to compute or describe any other`() {
        val rule = FeeRule.Fixed(mapOf("USD" to BigDecimal("0.50")))

        assertTrue(rule.hasFeeFor(usd))
        assertFalse(rule.hasFeeFor(jpy))
        assertThrows<IllegalArgumentException> { rule.compute(BigDecimal("1000"), jpy) }
        assertThrows<IllegalArgumentException> { rule.describe(jpy) }
    }

    @Test
    fun `no fee is zero in every asset, with rate 0 and its own description`() {
        val rule = FeeRule.NoFee("No fee for charity donations")

        assertEquals(0, rule.compute(BigDecimal("1000"), usd).signum())
        assertEquals(0, rule.compute(BigDecimal("0.00000001"), btc).signum())
        assertEquals(0, rule.nominalRate().signum())
        assertEquals("No fee for charity donations", rule.describe(btc))
    }
}
