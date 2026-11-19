package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The registry contract: every registered type keeps the rules a new entry could break.
 * Adding a type to [DefaultFeeSchedule] adds it here, with no extra test code.
 */
class FeeScheduleContractTest {

    private fun typeOf(label: String): TransactionType = checkNotNull(SCHEDULE.find(label)) { "$label is not registered" }

    /** The registered assets whose asset type [type] allows. */
    private fun allowedAssets(type: TransactionType): List<Asset> = ASSETS.assets.filter { it.type in type.allowedAssetTypes }

    /**
     * Amounts across the whole range an amount may take in [asset], from its smallest unit up to
     * 20 integer digits.
     */
    private fun sampleAmounts(asset: Asset): List<BigDecimal> =
        listOf(
            BigDecimal.ONE.movePointLeft(asset.scale),
            BigDecimal("0.5").setScale(asset.scale, RoundingMode.DOWN).takeIf { it.signum() > 0 },
            BigDecimal.ONE,
            BigDecimal("1000"),
            BigDecimal("12345678901234567.89").setScale(asset.scale, RoundingMode.DOWN),
            BigDecimal("99999999999999999999"),
        ).filterNotNull()

    @Test
    fun `the contract has types to check`() {
        assertTrue(SCHEDULE.types.isNotEmpty())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `allows at least one registered asset`(label: String) {
        assertTrue(allowedAssets(typeOf(label)).isNotEmpty()) { "$label allows no registered asset" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `has a description with no unfilled placeholder in each allowed asset`(label: String) {
        val type = typeOf(label)
        allowedAssets(type).forEach { asset ->
            val description = type.rule.describe(asset)
            assertTrue(description.isNotBlank()) { "$label has a blank description in ${asset.code}" }
            assertFalse(PLACEHOLDER.containsMatchIn(description)) { "$label in ${asset.code}: an unfilled placeholder in \"$description\"" }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `charges a fee of at least zero for sample amounts in each allowed asset`(label: String) {
        val type = typeOf(label)
        allowedAssets(type).forEach { asset ->
            sampleAmounts(asset).forEach { amount ->
                val raw = type.rule.compute(amount, asset)
                assertTrue(raw.signum() >= 0) { "$label: ${raw.toPlainString()} for ${amount.toPlainString()} ${asset.code}" }

                // Through the calculator: priced at the asset's scale and never more than the
                // amount, or refused only because the fee is larger than the amount.
                val money = Money(amount, asset)
                when (val calculation = CALCULATOR.quote(money, label)) {
                    is FeeCalculation.Quoted -> {
                        val fee = calculation.quote.fee
                        assertTrue(fee.amount.signum() >= 0 && fee <= money) { "$label: fee ${fee.plain()} for ${amount.toPlainString()} ${asset.code}" }
                        assertEquals(asset, fee.asset, "$label in ${asset.code}")
                        assertEquals(asset.scale, fee.amount.scale(), "$label in ${asset.code}")
                    }
                    is FeeCalculation.Rejected ->
                        assertEquals(listOf(TransactionField.AMOUNT), calculation.violations.map { it.field }) {
                            "$label refused ${amount.toPlainString()} ${asset.code}: ${calculation.violations}"
                        }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `has a nominal rate in 0 to 1`(label: String) {
        val rate = typeOf(label).rule.nominalRate()

        assertTrue(rate.signum() >= 0 && rate < BigDecimal.ONE) { "$label: rate ${rate.toPlainString()}" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `a fixed fee has a fee for every allowed asset of its asset types`(label: String) {
        val type = typeOf(label)
        val missing = when (val rule = type.rule) {
            is FeeRule.Fixed -> allowedAssets(type).filterNot(rule::hasFeeFor).map { it.code }
            is FeeRule.Percentage, is FeeRule.NoFee -> emptyList()
        }

        assertEquals(emptyList<String>(), missing) { "$label has no fixed fee for $missing" }
    }

    companion object {
        private val ASSETS: AssetRegistry = DefaultAssetRegistry()
        private val SCHEDULE: FeeSchedule = DefaultFeeSchedule()
        private val CALCULATOR: FeeCalculationService = FeeCalculationServiceImpl(ASSETS, SCHEDULE)

        /** `{asset}`, `${x}`, `$x`, `%s`, `%1$s`, `<asset>`, `null`, `TODO`: text a template left behind. */
        private val PLACEHOLDER = Regex("""[{}]|\$\w|%(\d+\$)?[a-zA-Z]|<\w+>|\bnull\b|\bTODO\b""")

        @JvmStatic
        fun labels(): List<String> = SCHEDULE.labels()
    }
}
