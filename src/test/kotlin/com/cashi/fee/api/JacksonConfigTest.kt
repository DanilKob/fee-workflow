package com.cashi.fee.api

import com.cashi.fee.domain.FeeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/** The number serializer and the request binding, on the mapper as Spring MVC builds it. */
class JacksonConfigTest {

    private val mapper: JsonMapper = JacksonConfig.configure(JsonMapper.builder().findAndAddModules()).build()

    @Test
    fun `the 200 body is the brief's seven snake_case keys, with numbers in plain form`() {
        val result = FeeResult(
            transactionId = "txn_001",
            amount = BigDecimal("1000.00"),
            asset = "USD",
            type = "Mobile Top Up",
            fee = BigDecimal("1.50"),
            rate = BigDecimal("0.0015"),
            description = "Standard fee rate of 0.15%",
        )

        assertEquals(
            """{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.5,"rate":0.0015,"description":"Standard fee rate of 0.15%"}""",
            mapper.writeValueAsString(TransactionFeeResponse.from(result)),
        )
    }

    @Test
    fun `large and tiny values keep every digit and never use an exponent`() {
        val result = FeeResult("t", BigDecimal("12345678901234567.89"), "USD", "x", BigDecimal("18518518351851.85"), BigDecimal("1E-18"), "d")

        val json = mapper.writeValueAsString(TransactionFeeResponse.from(result))

        assertTrue(json.contains(""""amount":12345678901234567.89,""")) { json }
        assertTrue(json.contains(""""fee":18518518351851.85,""")) { json }
        assertTrue(json.contains(""""rate":0.000000000000000001,""")) { json }
    }

    @Test
    fun `the request binds snake_case keys and reads a JSON float as an exact BigDecimal`() {
        val request = mapper.readValue(
            """{"transaction_id":"txn_001","amount":0.1000000000000000055511151231257827,"asset":"USD","asset_type":"FIAT",
               |"type":"Mobile Top Up","state":"SETTLED - PENDING FEE","created_at":"2023-08-30 15:42:17.610059"}""".trimMargin(),
            TransactionFeeRequest::class.java,
        )

        assertEquals("txn_001", request.transactionId?.stringValue())
        assertEquals("FIAT", request.assetType?.stringValue())
        assertEquals("2023-08-30 15:42:17.610059", request.createdAt?.stringValue())
        val amount = checkNotNull(request.amount)
        assertTrue(amount.isBigDecimal) { "a float must never pass through double: ${amount.nodeType}" }
        assertEquals(BigDecimal("0.1000000000000000055511151231257827"), amount.decimalValue())
    }

    @Test
    fun `an amount sent as a JSON string stays a string node, so it can be refused`() {
        val request = mapper.readValue("""{"amount":"1000"}""", TransactionFeeRequest::class.java)

        assertTrue(request.amount!!.isString)
    }
}
