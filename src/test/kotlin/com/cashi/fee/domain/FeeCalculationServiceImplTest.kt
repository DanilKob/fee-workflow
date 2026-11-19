package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/**
 * The fee rules and the price. An amount reaches [FeeCalculationService.quote] as [Money], so its
 * bounds already hold and no amount here can expand an exponent.
 */
class FeeCalculationServiceImplTest {

    private val calculator = FeeCalculationServiceImpl(ASSETS, DefaultFeeSchedule())

    /** [amount] in [asset]: a registered asset, or a stand-in for one the registry doesn't hold. */
    private fun money(amount: String, asset: String = "USD"): Money = Money(BigDecimal(amount), ASSETS.find(asset) ?: Asset(asset, AssetType.FIAT, 2))

    private fun quote(amount: String, asset: String = "USD", type: String = MOBILE_TOP_UP) = calculator.quote(money(amount, asset), type)

    @Test
    fun `the brief's sample is priced at 0_15 percent with the standard description`() {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote("1000")).quote

        assertEquals(Money(BigDecimal("1.50"), USD), quote.fee)
        assertEquals(BigDecimal("0.0015"), quote.rate)
        assertEquals("Standard fee rate of 0.15%", quote.description)
        assertEquals(MOBILE_TOP_UP, quote.type)
        assertEquals("USD", quote.asset)
        assertEquals("mobile-top-up", quote.ruleId)
        assertEquals(1, quote.ruleVersion)
    }

    @ParameterizedTest(name = "{0} USD -> {1}")
    @CsvSource(
        "1000, 1.50", // the brief
        "10, 0.02", // 0.015 rounds up; Double gives 0.01
        "330, 0.50", // 0.495 rounds up; Double gives 0.49
        "30, 0.05", // 0.045: HALF_UP gives 0.05, HALF_EVEN 0.04
        "0.01, 0.00", // a fee that rounds to zero is still a fee
        "12345678901234567.89, 18518518351851.85", // 18518518351851.851835, every digit kept
    )
    fun `rounds once, HALF_UP, at the asset's scale`(amount: String, fee: String) {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote(amount)).quote

        assertEquals(BigDecimal(fee), quote.fee.amount)
        assertEquals(2, quote.fee.amount.scale())
    }

    /**
     * Every registered asset, mostly on a half-up tie at its own scale.
     * These are the per-asset rows of money.feature, at unit-test size.
     */
    @ParameterizedTest(name = "{0} {1} -> {3}")
    @CsvSource(
        "12345.67, USD, FIAT, 18.52", // 18.518505
        "30, EUR, FIAT, 0.05", // 0.045: a tie, HALF_UP
        "330, GBP, FIAT, 0.50", // 0.495
        "1000, JPY, FIAT, 2", // 1.5 at scale 0
        "3000, JPY, FIAT, 5", // 4.5: a tie, HALF_EVEN would give 4
        "0.12345678, BTC, CRYPTO, 0.00018519", // 0.00018518517
        "0.00003, BTC, CRYPTO, 0.00000005", // 0.000000045: a tie at scale 8
        "1.234567890123456789, ETH, CRYPTO, 0.001851851835185185", // 18 decimals kept, past what a Double holds
        "0.000000000000003, ETH, CRYPTO, 0.000000000000000005", // 0.0000000000000000045: a tie at scale 18
        "0.003, USDC, CRYPTO, 0.000005", // 0.0000045: a tie at scale 6
        "1234.567891, USDT, CRYPTO, 1.851852", // 1.8518518365
    )
    fun `each asset rounds once, HALF_UP, at its own scale`(amount: String, asset: String, assetType: AssetType, fee: String) {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote(amount, asset)).quote

        assertEquals(BigDecimal(fee), quote.fee.amount)
        assertEquals(ASSETS.find(asset)!!.scale, quote.fee.amount.scale())
        assertEquals(assetType, quote.fee.asset.type)
        assertEquals(asset, quote.asset)
    }

    // An amount with more significant decimals than its asset's scale can't be Money.
    // MoneyTest and TransactionFactoryImplTest cover that, before any price is computed.

    @Test
    fun `trailing zeros are not decimals for any asset (Q13)`() {
        assertInstanceOf(FeeCalculation.Quoted::class.java, quote("1000.000", "JPY"))
        assertInstanceOf(FeeCalculation.Quoted::class.java, quote("0.123456780000", "BTC"))
    }

    @Test
    fun `an unknown type is refused with the valid labels, never priced with a default`() {
        val rejected = assertInstanceOf(FeeCalculation.Rejected::class.java, quote("1000", type = "Mobile top up"))

        assertEquals(
            listOf(RuleViolation(TransactionField.TYPE, "unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation")),
            rejected.violations,
        )
    }

    /** Bank Transfer is a flat fee per fiat asset, whatever the amount. Its rate is 0. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "1000      | USD | 0.50 | Flat fee of 0.50 USD",
            "0.51      | EUR | 0.50 | Flat fee of 0.50 EUR",
            "123456.78 | GBP | 0.50 | Flat fee of 0.50 GBP",
            "1000      | JPY | 75   | Flat fee of 75 JPY",
        ],
    )
    fun `Bank Transfer charges its flat fee in each fiat asset`(amount: String, asset: String, fee: String, description: String) {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote(amount, asset, type = BANK_TRANSFER)).quote

        assertEquals(BigDecimal(fee), quote.fee.amount)
        assertEquals(ASSETS.find(asset)!!.scale, quote.fee.amount.scale())
        assertEquals(0, quote.rate.signum())
        assertEquals(description, quote.description)
        assertEquals(BANK_TRANSFER, quote.type)
        assertEquals("bank-transfer", quote.ruleId)
        assertEquals(1, quote.ruleVersion)
    }

    /**
     * Charity Donation is free in every asset, fiat and crypto. It is still a quote, so a zero
     * charge is still made.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "1000, USD, FIAT",
        "0.01, USD, FIAT",
        "1, JPY, FIAT",
        "0.00000001, BTC, CRYPTO",
        "1.234567890123456789, ETH, CRYPTO",
    )
    fun `Charity Donation charges no fee in any asset`(amount: String, asset: String, assetType: AssetType) {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote(amount, asset, CHARITY_DONATION)).quote

        assertEquals(0, quote.fee.amount.signum())
        assertEquals(assetType, quote.fee.asset.type)
        assertEquals(ASSETS.find(asset)!!.scale, quote.fee.amount.scale())
        assertEquals(0, quote.rate.signum())
        assertEquals("No fee for charity donations", quote.description)
        assertEquals("charity-donation", quote.ruleId)
    }

    /**
     * A fee larger than the amount is refused, reported on the amount.
     * A fee equal to the amount is charged.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.10 | USD | must be at least the fee of 0.50 USD",
            "0.49 | GBP | must be at least the fee of 0.50 GBP",
            "74   | JPY | must be at least the fee of 75 JPY",
        ],
    )
    fun `a fee larger than the amount is refused`(amount: String, asset: String, message: String) {
        assertEquals(
            FeeCalculation.Rejected(listOf(RuleViolation(TransactionField.AMOUNT, message))),
            quote(amount, asset, type = BANK_TRANSFER),
        )
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("0.50, USD", "0.5, EUR", "0.500, GBP", "75, JPY")
    fun `a fee equal to the amount is charged`(amount: String, asset: String) {
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, quote(amount, asset, type = BANK_TRANSFER)).quote

        assertEquals(0, quote.fee.compareTo(money(amount, asset)))
    }

    /** A type's allowed asset types are checked against the registered asset's own asset type. */
    @ParameterizedTest(name = "Bank Transfer in {0}")
    @ValueSource(strings = ["BTC", "ETH", "USDC", "USDT"])
    fun `a type used with an asset type it doesn't allow is refused on the type`(asset: String) {
        assertEquals(
            FeeCalculation.Rejected(listOf(RuleViolation(TransactionField.TYPE, BANK_TRANSFER_NOT_CRYPTO))),
            quote("1000", asset, BANK_TRANSFER),
        )
    }

    @Test
    fun `a mismatched asset type neither hides nor invents a type violation`() {
        // BTC sent as FIAT: the asset type is wrong, and BTC, a crypto asset, is still not allowed.
        assertEquals(
            listOf(RuleViolation(TransactionField.ASSET_TYPE, "BTC is CRYPTO"), RuleViolation(TransactionField.TYPE, BANK_TRANSFER_NOT_CRYPTO)),
            calculator.checkRules("BTC", AssetType.FIAT, BANK_TRANSFER),
        )
        // USD sent as CRYPTO: only the asset type is wrong. Bank Transfer allows fiat USD.
        assertEquals(
            listOf(RuleViolation(TransactionField.ASSET_TYPE, "USD is FIAT")),
            calculator.checkRules("USD", AssetType.CRYPTO, BANK_TRANSFER),
        )
        // An unknown asset: the stated asset type is all there is to check the type against.
        assertEquals(
            listOf(RuleViolation(TransactionField.ASSET, "unknown asset"), RuleViolation(TransactionField.TYPE, BANK_TRANSFER_NOT_CRYPTO)),
            calculator.checkRules("XYZ", AssetType.CRYPTO, BANK_TRANSFER),
        )
    }

    /**
     * A fixed fee with no amount configured for the asset is refused, never priced. The shipped
     * schedule configures every fiat asset, so only a test schedule can reach this case.
     */
    @Test
    fun `a fixed fee with no fee for the asset is refused on the asset`() {
        val schedule = object : FeeSchedule {
            override val types = listOf(
                TransactionType("USD Transfer", FeeRule.Fixed(mapOf("USD" to BigDecimal("0.50"))), setOf(AssetType.FIAT), "usd-transfer", 1),
            )
        }
        val calculator = FeeCalculationServiceImpl(DefaultAssetRegistry(), schedule)

        assertEquals(
            FeeCalculation.Rejected(listOf(RuleViolation(TransactionField.ASSET, "USD Transfer has no fee configured for EUR"))),
            calculator.quote(money("1000", "EUR"), "USD Transfer"),
        )
        // A disallowed asset type is reported once, on the type, not again as a missing fee.
        assertEquals(
            listOf(RuleViolation(TransactionField.TYPE, "USD Transfer does not allow CRYPTO; allowed asset types: FIAT")),
            calculator.checkRules("BTC", AssetType.CRYPTO, "USD Transfer"),
        )
        assertInstanceOf(FeeCalculation.Quoted::class.java, calculator.quote(money("1000"), "USD Transfer"))
    }

    /**
     * A null argument is a field the caller has already reported. Its rules are skipped, and the
     * others still run, so every bad field is listed.
     */
    @Test
    fun `a field already reported skips only the rules that need it`() {
        assertEquals(emptyList<RuleViolation>(), calculator.checkRules(null, null, null))
        assertEquals(
            listOf(RuleViolation(TransactionField.TYPE, BANK_TRANSFER_NOT_CRYPTO)),
            calculator.checkRules("BTC", null, BANK_TRANSFER),
        )
        assertEquals(
            listOf(RuleViolation(TransactionField.TYPE, BANK_TRANSFER_NOT_CRYPTO)),
            calculator.checkRules(null, AssetType.CRYPTO, BANK_TRANSFER),
        )
        assertEquals(listOf(TransactionField.ASSET), calculator.checkRules("XYZ", null, null).map { it.field })
        assertEquals(listOf(TransactionField.TYPE), calculator.checkRules(null, null, "Nope").map { it.field })
    }

    @Test
    fun `an unknown asset and an asset type that disagrees with the asset are refused`() {
        // Money in an asset the registry doesn't hold is never priced.
        val unknown = assertInstanceOf(FeeCalculation.Rejected::class.java, quote("1000", asset = "usd"))

        assertEquals(listOf(TransactionField.ASSET), unknown.violations.map { it.field })
        assertEquals(listOf(RuleViolation(TransactionField.ASSET_TYPE, "USD is FIAT")), calculator.checkRules("USD", AssetType.CRYPTO, MOBILE_TOP_UP))
    }

    @Test
    fun `every violation is reported at once`() {
        val rejected = assertInstanceOf(FeeCalculation.Rejected::class.java, quote("1000", asset = "XXX", type = "Nope"))

        assertEquals(listOf(TransactionField.TYPE, TransactionField.ASSET), rejected.violations.map { it.field })
    }

    // The amount's own bounds (integer digits, decimals, and huge or tiny exponents refused fast)
    // belong to Money and the factory, and MoneyTest and TransactionFactoryImplTest cover them.
    // Those tests also list an amount violation together with a rule violation.

    @Test
    fun `the rules without an amount are checked on their own`() {
        assertEquals(listOf(RuleViolation(TransactionField.ASSET_TYPE, "USD is FIAT")), calculator.checkRules("USD", AssetType.CRYPTO, MOBILE_TOP_UP))
        assertEquals(emptyList<RuleViolation>(), calculator.checkRules("USD", AssetType.FIAT, MOBILE_TOP_UP))
        assertEquals(listOf(RuleViolation(TransactionField.ASSET_TYPE, "USD is FIAT")), calculator.checkRules("USD", AssetType.CRYPTO, null))
    }

    @Test
    fun `a transaction is priced from its own amount and type`() {
        val transaction = Transaction(
            "txn_001", money("1000"), AssetType.FIAT, MOBILE_TOP_UP, Transaction.ELIGIBLE_STATE,
            DateFormatter.parse("2023-08-30 15:42:17.610059")!!,
        )

        assertEquals(quote("1000"), calculator.quote(transaction))
    }

    /** A rejection without a reason would be a bug: the caller could list no field. */
    @Test
    fun `a rejection has at least one reason`() {
        assertThrows<IllegalArgumentException> { FeeCalculation.Rejected(emptyList()) }
    }

    private companion object {
        val ASSETS = DefaultAssetRegistry()
        val USD = ASSETS.find("USD")!!
        const val MOBILE_TOP_UP = "Mobile Top Up"
        const val BANK_TRANSFER = "Bank Transfer"
        const val CHARITY_DONATION = "Charity Donation"
        const val BANK_TRANSFER_NOT_CRYPTO = "Bank Transfer does not allow CRYPTO; allowed asset types: FIAT"
    }
}
