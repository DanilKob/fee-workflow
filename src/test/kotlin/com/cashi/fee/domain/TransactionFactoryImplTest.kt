package com.cashi.fee.domain

import com.cashi.fee.domain.TransactionField.AMOUNT
import com.cashi.fee.domain.TransactionField.ASSET
import com.cashi.fee.domain.TransactionField.ASSET_TYPE
import com.cashi.fee.domain.TransactionField.CREATED_AT
import com.cashi.fee.domain.TransactionField.STATE
import com.cashi.fee.domain.TransactionField.TRANSACTION_ID
import com.cashi.fee.domain.TransactionField.TYPE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

/**
 * The one way to build a transaction (Q19): every domain check, every violation at once, each field
 * at most once, in the words the API shows (AC26, AC27).
 */
class TransactionFactoryImplTest {

    private val assets = DefaultAssetRegistry()
    private val factory = TransactionFactoryImpl(assets, FeeCalculationServiceImpl(assets, DefaultFeeSchedule()))

    private fun input(
        transactionId: String? = "txn_001",
        amount: String? = "1000",
        asset: String? = "USD",
        assetType: String? = "FIAT",
        type: String? = "Mobile Top Up",
        state: String? = Transaction.ELIGIBLE_STATE,
        createdAt: String? = "2023-08-30 15:42:17.610059",
    ) = TransactionInput(transactionId, amount?.let(::BigDecimal), asset, assetType, type, state, createdAt)

    private fun created(input: TransactionInput): Transaction =
        assertInstanceOf(TransactionCreation.Created::class.java, factory.create(input)).transaction

    private fun violations(input: TransactionInput): List<RuleViolation> =
        assertInstanceOf(TransactionCreation.Invalid::class.java, factory.create(input)).violations

    @Test
    fun `the brief's sample becomes a transaction with the exact values`() {
        assertEquals(
            Transaction(
                transactionId = "txn_001",
                amount = Money(BigDecimal("1000"), assets.find("USD")!!),
                assetType = AssetType.FIAT,
                type = "Mobile Top Up",
                state = "SETTLED - PENDING FEE",
                createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
            ),
            created(input()),
        )
        assertEquals("USD", created(input()).asset)
    }

    @Test
    fun `every missing field is listed once, in field order`() {
        assertEquals(
            listOf(TRANSACTION_ID, AMOUNT, ASSET, ASSET_TYPE, TYPE, STATE, CREATED_AT).map { RuleViolation(it, "is required") },
            violations(TransactionInput(null, null, null, null, null, null, null)),
        )
    }

    /** The API relies on it: a field it refused for its JSON type arrives as null and gets one violation, `is required`. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TransactionField::class)
    fun `a missing field gets exactly one violation, and hides no other field's`(field: TransactionField) {
        val missing = when (field) {
            TRANSACTION_ID -> input(transactionId = null)
            AMOUNT -> input(amount = null)
            ASSET -> input(asset = null)
            ASSET_TYPE -> input(assetType = null)
            TYPE -> input(type = null)
            STATE -> input(state = null)
            CREATED_AT -> input(createdAt = null)
        }

        assertEquals(listOf(RuleViolation(field, "is required")), violations(missing))
    }

    @Test
    fun `a blank id, asset or type is missing, and a blank asset type, state or created_at is malformed`() {
        assertEquals(listOf(RuleViolation(TRANSACTION_ID, "is required")), violations(input(transactionId = "  ")))
        assertEquals(listOf(RuleViolation(ASSET, "is required")), violations(input(asset = "")))
        assertEquals(listOf(RuleViolation(TYPE, "is required")), violations(input(type = " ")))
        assertEquals(listOf(RuleViolation(ASSET_TYPE, "must be one of FIAT, CRYPTO")), violations(input(assetType = "")))
        assertEquals(listOf(RuleViolation(STATE, "must be SETTLED - PENDING FEE")), violations(input(state = "")))
        assertEquals(listOf(RuleViolation(CREATED_AT, "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS")), violations(input(createdAt = "")))
    }

    /**
     * Each bound is read from precision and scale, so a huge or a tiny exponent is refused at once:
     * the bounds for every asset first, then the sign, then the asset's own scale. Pricing
     * `1E-500000000` would compute 10^500000000 in `setScale`: minutes of CPU per request.
     */
    @ParameterizedTest(name = "amount {0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "0                     | must be greater than zero",
            "-1                    | must be greater than zero",
            "-0.001                | must be greater than zero",
            "100000000000000000000 | must have at most 20 integer digits",
            "1E+30                 | must have at most 20 integer digits",
            "1E+20000000           | must have at most 20 integer digits",
            "1E+2147483647         | must have at most 20 integer digits",
            "-1E+2147483647        | must have at most 20 integer digits",
            "1E-30                 | must have at most 18 decimal places",
            "1e-500000000          | must have at most 18 decimal places",
            "0.000000000000000001  | has more decimals than USD allows (2)",
            "10.001                | has more decimals than USD allows (2)",
        ],
    )
    fun `an amount outside what can be priced and stored is refused at once`(amount: String, message: String) {
        val input = input(amount = amount)
        factory.create(input()) // warm up, so the bound below times the amount alone

        val creation = assertTimeoutPreemptively(Duration.ofMillis(100)) { factory.create(input) }

        assertEquals(TransactionCreation.Invalid(listOf(RuleViolation(AMOUNT, message))), creation)
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "1000.5                | JPY  | FIAT   | has more decimals than JPY allows (0)",
            "0.123456789           | BTC  | CRYPTO | has more decimals than BTC allows (8)",
            "1.0000001             | USDT | CRYPTO | has more decimals than USDT allows (6)",
            "1.0000000000000000001 | ETH  | CRYPTO | must have at most 18 decimal places",
        ],
    )
    fun `an amount with more decimals than its asset allows is refused`(amount: String, asset: String, assetType: String, message: String) {
        assertEquals(listOf(RuleViolation(AMOUNT, message)), violations(input(amount = amount, asset = asset, assetType = assetType)))
    }

    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource(
        "1000, JPY, FIAT",
        "1000.0, JPY, FIAT", // trailing zeros don't count, even at scale 0 (Q13)
        "0.12345678, BTC, CRYPTO",
        "1.234567890123456789, ETH, CRYPTO",
        "0.003, USDC, CRYPTO",
        "99.99, EUR, FIAT",
        "10.000, USD, FIAT",
        "99999999999999999999.99, USD, FIAT",
    )
    fun `an amount within its asset's bounds is kept as sent, in that asset`(amount: String, asset: String, assetType: AssetType) {
        val transaction = created(input(amount = amount, asset = asset, assetType = assetType.name))

        assertEquals(Money(BigDecimal(amount), assets.find(asset)!!), transaction.amount)
        assertEquals(BigDecimal(amount).scale(), transaction.amount.amount.scale())
        assertEquals(asset, transaction.asset)
        assertEquals(assetType, transaction.assetType)
    }

    @Test
    fun `an unknown asset still has its amount checked against the bounds for every asset`() {
        assertEquals(
            listOf(RuleViolation(AMOUNT, "must have at most 20 integer digits"), RuleViolation(ASSET, "unknown asset")),
            violations(input(amount = "1E+30", asset = "XYZ")),
        )
        // The asset's own scale needs the asset: 10.001 is no violation until the asset is known.
        assertEquals(listOf(RuleViolation(ASSET, "unknown asset")), violations(input(amount = "10.001", asset = "XYZ")))
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = ["0", "-1", "1E+30", "1e-500000000", "10.001"])
    fun `an invalid amount hides no rule violation, and no field is listed twice`(amount: String) {
        val violations = violations(input(amount = amount, assetType = "CRYPTO"))

        assertEquals(listOf(AMOUNT, ASSET_TYPE), violations.map { it.field })
        assertEquals(RuleViolation(ASSET_TYPE, "USD is FIAT"), violations.last())
    }

    @Test
    fun `labels match exactly, and an unknown type lists the valid ones`() {
        assertEquals(listOf(RuleViolation(ASSET, "unknown asset")), violations(input(asset = "usd")))
        assertEquals(listOf(RuleViolation(ASSET_TYPE, "must be one of FIAT, CRYPTO")), violations(input(assetType = "fiat")))
        assertEquals(
            listOf(RuleViolation(TYPE, "unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation")),
            violations(input(type = "mobile top up")),
        )
    }

    @Test
    fun `an asset of the other asset type, and a type that doesn't allow the asset's type, are refused by the fee rules`() {
        assertEquals(listOf(RuleViolation(ASSET_TYPE, "BTC is CRYPTO")), violations(input(asset = "BTC", amount = "1")))
        assertEquals(
            listOf(RuleViolation(TYPE, "Bank Transfer does not allow CRYPTO; allowed asset types: FIAT")),
            violations(input(type = "Bank Transfer", asset = "BTC", assetType = "CRYPTO")),
        )
    }

    /** Pricing is the calculation service's: a fee larger than the amount is no reason to refuse the transaction itself. */
    @Test
    fun `the factory doesn't price`() {
        val transaction = created(input(type = "Bank Transfer", amount = "0.10"))

        assertEquals(BigDecimal("0.10"), transaction.amount.amount)
    }

    @Test
    fun `only the eligible state is accepted, and created_at only in the strict format`() {
        assertEquals(listOf(RuleViolation(STATE, "must be SETTLED - PENDING FEE")), violations(input(state = "SETTLED")))
        listOf("2023-08-30T15:42:17.610059Z", "2023-08-30 15:42:17", "2023-08-30 15:42:17.610", "2023-08-30 25:00:00.000000", "+294277-01-01 00:00:00.000000", "-5000-01-01 00:00:00.000000").forEach { createdAt ->
            assertEquals(listOf(RuleViolation(CREATED_AT, "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS")), violations(input(createdAt = createdAt)), createdAt)
        }
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0, 0, 1_000), created(input(createdAt = "2024-02-29 00:00:00.000001")).createdAt)
    }

    @ParameterizedTest(name = "transaction_id {0}")
    @ValueSource(strings = ["txn 001", "txn.001", "txn_é", "txn%2F001"])
    fun `an id outside the pattern is refused, and one of 64 characters is accepted`(id: String) {
        assertEquals(listOf(RuleViolation(TRANSACTION_ID, Transaction.ID_RULE)), violations(input(transactionId = id)))
        assertEquals(listOf(RuleViolation(TRANSACTION_ID, Transaction.ID_RULE)), violations(input(transactionId = "a".repeat(65))))
        assertEquals("a".repeat(64), created(input(transactionId = "a".repeat(64))).transactionId)
    }

    /** AC27: every field invalid at once, each listed exactly once: the fields in order, then the rules. */
    @Test
    fun `every field invalid at once is listed once each`() {
        val violations = violations(
            input(transactionId = "txn 001", amount = "0", asset = "XYZ", assetType = "fiat", type = "Nope", state = "SETTLED", createdAt = "2023-08-30"),
        )

        assertEquals(listOf(TRANSACTION_ID, AMOUNT, ASSET_TYPE, STATE, CREATED_AT, TYPE, ASSET), violations.map { it.field })
    }

    @Test
    fun `an invalid transaction has at least one reason`() {
        assertThrows<IllegalArgumentException> { TransactionCreation.Invalid(emptyList()) }
    }
}
