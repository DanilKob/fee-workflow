package com.cashi.fee.api

import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.DefaultAssetRegistry
import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeCalculationServiceImpl
import com.cashi.fee.domain.Money
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionFactoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

/**
 * The validator lists every invalid field at once. Labels match exactly, and an amount must fit the
 * record and its asset's scale.
 */
class FeeRequestValidationServiceImplTest {

    private val mapper: JsonMapper = JacksonConfig.configure(JsonMapper.builder().findAndAddModules()).build()
    private val assets = DefaultAssetRegistry()
    private val calculation = FeeCalculationServiceImpl(assets, DefaultFeeSchedule())
    private val validator = FeeRequestValidationServiceImpl(TransactionFactoryImpl(assets, calculation), calculation, assets)

    private fun request(vararg overrides: Pair<String, String?>): TransactionFeeRequest {
        val fields = linkedMapOf(
            "transaction_id" to "\"txn_001\"",
            "amount" to "1000",
            "asset" to "\"USD\"",
            "asset_type" to "\"FIAT\"",
            "type" to "\"Mobile Top Up\"",
            "state" to "\"SETTLED - PENDING FEE\"",
            "created_at" to "\"2023-08-30 15:42:17.610059\"",
        )
        overrides.forEach { (key, raw) -> if (raw == null) fields.remove(key) else fields[key] = raw }
        val json = fields.entries.joinToString(",", "{", "}") { (key, raw) -> "\"$key\":$raw" }
        return mapper.readValue(json, TransactionFeeRequest::class.java)
    }

    private fun errorsOf(request: TransactionFeeRequest): List<InvalidField> =
        assertThrows<RequestValidationException> { validator.validate(request) }.errors

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
            validator.validate(request()),
        )
    }

    @Test
    fun `every missing field is listed in one rejection`() {
        assertEquals(
            listOf("transaction_id", "amount", "asset", "asset_type", "type", "state", "created_at"),
            errorsOf(mapper.readValue("{}", TransactionFeeRequest::class.java)).map { it.field },
        )
        assertEquals(listOf(InvalidField("amount", "is required")), errorsOf(request("amount" to "null")))
    }

    @Test
    fun `several invalid fields are all listed`() {
        val errors = errorsOf(request("asset_type" to "\"fiat\"", "state" to "\"SETTLED\"", "created_at" to "\"2023-08-30\""))

        assertEquals(listOf("asset_type", "state", "created_at"), errors.map { it.field })
    }

    /**
     * Each bound is read from precision and scale, so a huge or a tiny exponent is refused at once.
     * Pricing `1E-500000000` would compute 10^500000000 in `setScale`: minutes of CPU per request.
     */
    @ParameterizedTest(name = "amount {0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "\"1000\"                   | must be a JSON number",
            "0                          | must be greater than zero",
            "-1                         | must be greater than zero",
            "100000000000000000000      | must have at most 20 integer digits",
            "1E+30                      | must have at most 20 integer digits",
            "1E+20000000                | must have at most 20 integer digits",
            "1e20000000                 | must have at most 20 integer digits",
            "1E+2147483647              | must have at most 20 integer digits",
            "1E-30                      | must have at most 18 decimal places",
            "1e-500000000               | must have at most 18 decimal places",
            "0.000000000000000001       | has more decimals than USD allows (2)",
            "10.001                     | has more decimals than USD allows (2)",
        ],
    )
    fun `an amount outside what can be priced and stored is refused`(amount: String, message: String) {
        val request = request("amount" to amount)
        validator.validate(request()) // warm up, so the bound below times the amount alone

        val errors = assertTimeoutPreemptively(Duration.ofMillis(100)) { errorsOf(request) }

        assertEquals(listOf(InvalidField("amount", message)), errors)
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = ["null", "\"1000\"", "0", "-1", "1E+30", "1e-500000000", "10.001"])
    fun `an invalid amount hides no rule violation, and no field is listed twice`(amount: String) {
        val errors = errorsOf(request("amount" to amount, "asset_type" to "\"CRYPTO\""))

        assertEquals(listOf("amount", "asset_type"), errors.map { it.field })
        assertEquals(InvalidField("asset_type", "USD is FIAT"), errors.last())
    }

    @ParameterizedTest(name = "type {0}")
    @NullSource
    @ValueSource(strings = ["null", "\"\"", "\"Nope\"", "\"mobile top up\""])
    fun `a missing or unknown type hides no asset_type violation, and no field is listed twice`(type: String?) {
        val errors = errorsOf(request("type" to type, "asset_type" to "\"CRYPTO\""))

        assertEquals(listOf("type", "asset_type"), errors.map { it.field })
        assertEquals(InvalidField("asset_type", "USD is FIAT"), errors.last())
    }

    @Test
    fun `trailing zeros don't count as decimals, and 20 integer digits fit`() {
        assertEquals(BigDecimal("10.000"), validator.validate(request("amount" to "10.000")).amount.amount)
        assertEquals(BigDecimal("99999999999999999999.99"), validator.validate(request("amount" to "99999999999999999999.99")).amount.amount)
    }

    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource(
        "1000, JPY, FIAT",
        "1000.0, JPY, FIAT", // trailing zeros don't count, even at scale 0
        "0.12345678, BTC, CRYPTO",
        "1.234567890123456789, ETH, CRYPTO",
        "0.003, USDC, CRYPTO",
        "99.99, EUR, FIAT",
    )
    fun `every registered asset is accepted, fiat and crypto`(amount: String, asset: String, assetType: AssetType) {
        val transaction = validator.validate(request("amount" to amount, "asset" to "\"$asset\"", "asset_type" to "\"$assetType\""))

        assertEquals(BigDecimal(amount), transaction.amount.amount)
        assertEquals(asset, transaction.asset)
        assertEquals(assetType, transaction.assetType)
    }

    @ParameterizedTest(name = "{0} {1}: {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "1000.5                | JPY  | FIAT   | has more decimals than JPY allows (0)",
            "0.123456789           | BTC  | CRYPTO | has more decimals than BTC allows (8)",
            "1.0000001             | USDT | CRYPTO | has more decimals than USDT allows (6)",
            "1.0000000000000000001 | ETH  | CRYPTO | must have at most 18 decimal places",
        ],
    )
    fun `an amount with more decimals than its asset allows is refused`(amount: String, asset: String, assetType: AssetType, message: String) {
        val errors = errorsOf(request("amount" to amount, "asset" to "\"$asset\"", "asset_type" to "\"$assetType\""))

        assertEquals(listOf(InvalidField("amount", message)), errors)
    }

    @Test
    fun `an asset of the other asset type is refused, for crypto as for fiat`() {
        assertEquals(listOf(InvalidField("asset_type", "BTC is CRYPTO")), errorsOf(request("asset" to "\"BTC\"")))
        assertEquals(listOf(InvalidField("asset_type", "JPY is FIAT")), errorsOf(request("asset" to "\"JPY\"", "asset_type" to "\"CRYPTO\"")))
    }

    @Test
    fun `labels match exactly, and an unknown type lists the valid ones`() {
        assertEquals(listOf(InvalidField("asset", "unknown asset")), errorsOf(request("asset" to "\"usd\"")))
        assertEquals(listOf(InvalidField("asset_type", "must be one of FIAT, CRYPTO")), errorsOf(request("asset_type" to "\"fiat\"")))
        assertEquals(
            listOf(InvalidField("type", "unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation")),
            errorsOf(request("type" to "\"mobile top up\"")),
        )
    }

    @Test
    fun `an unknown asset and an unknown type are both listed, in the fee rules' words`() {
        assertEquals(
            listOf(
                InvalidField("type", "unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation"),
                InvalidField("asset", "unknown asset"),
            ),
            errorsOf(request("asset" to "\"XYZ\"", "type" to "\"Nope\"")),
        )
    }

    @Test
    fun `a type used with an asset type it doesn't allow is refused on the type (AC15)`() {
        assertEquals(
            listOf(InvalidField("type", BANK_TRANSFER_NOT_CRYPTO)),
            errorsOf(request("type" to "\"Bank Transfer\"", "asset" to "\"BTC\"", "asset_type" to "\"CRYPTO\"")),
        )
    }

    @Test
    fun `a fee larger than the amount is refused on the amount, and a fee equal to it is accepted (AC13)`() {
        assertEquals(
            listOf(InvalidField("amount", "must be at least the fee of 0.50 USD")),
            errorsOf(request("type" to "\"Bank Transfer\"", "amount" to "0.10")),
        )
        assertEquals(BigDecimal("0.50"), validator.validate(request("type" to "\"Bank Transfer\"", "amount" to "0.50")).amount.amount)
    }

    @Test
    fun `every registered type is accepted, with its registry label`() {
        listOf("Mobile Top Up", "Bank Transfer", "Charity Donation").forEach { type ->
            assertEquals(type, validator.validate(request("type" to "\"$type\"")).type)
        }
        assertEquals("Charity Donation", validator.validate(request("type" to "\"Charity Donation\"", "amount" to "0.01")).type)
    }

    /**
     * A missing, malformed or refused field hides no type rule on the other fields.
     * No field is listed twice.
     */
    @Test
    fun `an invalid field hides no type rule, and no field is listed twice`() {
        val bankTransferInBtc = arrayOf("type" to "\"Bank Transfer\"", "asset" to "\"BTC\"", "asset_type" to "\"CRYPTO\"")

        assertEquals(
            listOf("amount", "created_at", "type"),
            errorsOf(request(*bankTransferInBtc, "amount" to "0", "created_at" to "\"2023-08-30\"")).map { it.field },
        )
        assertEquals(
            listOf(InvalidField("asset_type", "is required"), InvalidField("type", BANK_TRANSFER_NOT_CRYPTO)),
            errorsOf(request(*bankTransferInBtc, "asset_type" to null)),
        )
        assertEquals(
            listOf(InvalidField("asset", "is required"), InvalidField("type", BANK_TRANSFER_NOT_CRYPTO)),
            errorsOf(request(*bankTransferInBtc, "asset" to null)),
        )
        // BTC sent as FIAT: the asset type is wrong, and a crypto asset is still not allowed.
        assertEquals(
            listOf(InvalidField("asset_type", "BTC is CRYPTO"), InvalidField("type", BANK_TRANSFER_NOT_CRYPTO)),
            errorsOf(request(*bankTransferInBtc, "asset_type" to "\"FIAT\"")),
        )
    }

    @Test
    fun `a fee larger than the amount is listed with the fields the price doesn't need`() {
        assertEquals(
            listOf("state", "amount"),
            errorsOf(request("type" to "\"Bank Transfer\"", "amount" to "0.10", "state" to "\"SETTLED\"")).map { it.field },
        )
    }

    @Test
    fun `an asset type that disagrees with the asset is refused by the fee rules`() {
        assertEquals(listOf(InvalidField("asset_type", "USD is FIAT")), errorsOf(request("asset_type" to "\"CRYPTO\"")))
    }

    @Test
    fun `only the eligible state is accepted`() {
        assertEquals(listOf(InvalidField("state", "must be SETTLED - PENDING FEE")), errorsOf(request("state" to "\"SETTLED\"")))
    }

    /** Exactly six fraction digits, a space separator, no zone, and a real date-time. */
    @ParameterizedTest(name = "created_at {0}")
    @ValueSource(
        strings = [
            "2023-08-30T15:42:17.610059Z",
            "2023-08-30 15:42:17",
            "2023-08-30 15:42:17.610",
            "2023-08-30 25:00:00.000000",
            "+294277-01-01 00:00:00.000000",
            "-5000-01-01 00:00:00.000000",
            "2023-08-30T15:42:17.610059",
            "2023-08-30 15:42:17.6100590",
            "2023-08-30 15:42:17.610059Z",
            "2023-08-30 15:42:17.610059+02:00",
            "2023-02-30 15:42:17.610059",
            "2023-8-30 15:42:17.610059",
            " 2023-08-30 15:42:17.610059",
            "",
        ],
    )
    fun `created_at in any other format is refused (AC6)`(createdAt: String) {
        assertEquals(
            listOf(InvalidField("created_at", "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS")),
            errorsOf(request("created_at" to "\"$createdAt\"")),
        )
    }

    @Test
    fun `created_at keeps its microseconds and no zone (AC7)`() {
        assertEquals(LocalDateTime.of(2023, 2, 28, 23, 59, 59, 999_999_000), validator.validate(request("created_at" to "\"2023-02-28 23:59:59.999999\"")).createdAt)
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0, 0, 1_000), validator.validate(request("created_at" to "\"2024-02-29 00:00:00.000001\"")).createdAt)
    }

    @Test
    fun `transaction_id must fit the record's key and the id pattern`() {
        assertEquals(listOf("transaction_id"), errorsOf(request("transaction_id" to "\"${"a".repeat(65)}\"")).map { it.field })
        assertEquals(listOf("transaction_id"), errorsOf(request("transaction_id" to "\"txn/001\"")).map { it.field })
        assertEquals(listOf("transaction_id"), errorsOf(request("transaction_id" to "\"  \"")).map { it.field })
        assertEquals("a".repeat(64), validator.validate(request("transaction_id" to "\"${"a".repeat(64)}\"")).transactionId)
    }

    @ParameterizedTest(name = "transaction_id {0}: {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "''        | is required",
            "'   '     | is required",
            "txn 001   | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -",
            "txn.001   | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -",
            "txn_é     | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -",
            "txn%2F001 | must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -",
        ],
    )
    fun `a blank or out-of-pattern transaction_id is refused, in the body as in the path`(id: String, message: String) {
        assertEquals(listOf(InvalidField("transaction_id", message)), errorsOf(request("transaction_id" to "\"$id\"")))
        assertEquals(listOf(InvalidField("transaction_id", message)), assertThrows<RequestValidationException> { validator.validateId(id) }.errors)
    }

    @Test
    fun `a path transaction_id that fits the pattern is accepted as it is`() {
        assertEquals("txn_001-A", validator.validateId("txn_001-A"))
        assertEquals("a".repeat(64), validator.validateId("a".repeat(64)))
        assertEquals(listOf("transaction_id"), assertThrows<RequestValidationException> { validator.validateId("a".repeat(65)) }.errors.map { it.field })
    }

    /**
     * No number or boolean is coerced into an id or a label. Every field of the wrong JSON type is
     * listed at once.
     */
    @Test
    fun `a field of the wrong JSON type is refused, and every one is listed`() {
        val errors = errorsOf(
            request(
                "transaction_id" to "123",
                "asset" to "{\"code\":\"USD\"}",
                "asset_type" to "true",
                "type" to "[\"Mobile Top Up\"]",
                "state" to "1",
                "created_at" to "20230830",
            ),
        )

        assertEquals(
            listOf("transaction_id", "asset", "asset_type", "type", "state", "created_at").map { InvalidField(it, "must be a JSON string") },
            errors,
        )
    }

    /**
     * A field of the wrong JSON type takes the place its "is required" error would have, so the
     * order of the list doesn't change.
     */
    @Test
    fun `a field of the wrong JSON type is listed in its own place, among the factory's violations`() {
        assertEquals(
            listOf(InvalidField("transaction_id", "is required"), InvalidField("amount", "must be a JSON number")),
            errorsOf(request("transaction_id" to null, "amount" to "\"1000\"")),
        )
        assertEquals(
            listOf(InvalidField("transaction_id", "must be a JSON string"), InvalidField("amount", "must be at least the fee of 0.50 USD")),
            errorsOf(request("transaction_id" to "1", "type" to "\"Bank Transfer\"", "amount" to "0.10")),
        )
        assertEquals(
            listOf(InvalidField("amount", "must have at most 20 integer digits"), InvalidField("state", "must be a JSON string"), InvalidField("type", BANK_TRANSFER_NOT_CRYPTO)),
            errorsOf(request("amount" to "1E+30", "state" to "true", "type" to "\"Bank Transfer\"", "asset" to "\"BTC\"", "asset_type" to "\"CRYPTO\"")),
        )
    }

    @Test
    fun `a null field is missing, for every field`() {
        listOf("transaction_id", "amount", "asset", "asset_type", "type", "state", "created_at").forEach { field ->
            assertEquals(listOf(InvalidField(field, "is required")), errorsOf(request(field to "null")), field)
        }
    }

    @Test
    fun `every field invalid at once is listed once each`() {
        val errors = errorsOf(
            request(
                "transaction_id" to "\"txn 001\"",
                "amount" to "0",
                "asset" to "\"XYZ\"",
                "asset_type" to "\"fiat\"",
                "type" to "\"Nope\"",
                "state" to "\"SETTLED\"",
                "created_at" to "\"2023-08-30\"",
            ),
        )

        assertEquals(listOf("transaction_id", "amount", "asset_type", "state", "created_at", "type", "asset"), errors.map { it.field })
        assertEquals(errors.map { it.field }.toSet().size, errors.size)
    }

    private companion object {
        const val BANK_TRANSFER_NOT_CRYPTO = "Bank Transfer does not allow CRYPTO; allowed asset types: FIAT"
    }
}
