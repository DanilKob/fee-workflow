package com.cashi.fee

import com.cashi.fee.support.FeeIntegrationTest
import com.cashi.fee.support.Http
import com.cashi.fee.support.Observations
import com.cashi.fee.support.TestInfrastructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * AC30: the brief's sample payload through the real `POST /transaction/fee` over HTTP, with the app
 * on a random port against a real Restate server and a real Postgres (Testcontainers), its Restate
 * endpoint registered through the admin API. Asserts the raw body, one charge and one record.
 */
@FeeIntegrationTest
class FeeEndToEndIT {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val json: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    private fun postFee(body: String) = Http.postJson("http://localhost:$port/transaction/fee", body)

    @Test
    fun `the brief's sample transaction gets its fee, charged once and recorded once`() {
        val response = postFee(BRIEF_REQUEST)

        // AC1: status, content type and the brief's expected body, field by field.
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("application/json", response.headers().firstValue("content-type").orElse(null))
        assertEquals(json.readTree(BRIEF_RESPONSE), json.readTree(response.body()))

        // AC3: the raw text, not a parsed value.
        val body = response.body()
        assertRendered(body, "amount", "1000")
        assertRendered(body, "fee", "1.5")
        assertRendered(body, "rate", "0.0015")

        // One fee_record row, with the right values.
        val rows = Observations.feeRecords(jdbc, "txn_001")
        assertEquals(1, rows.size, rows.toString())
        val row = rows.single()
        assertEquals(0, BigDecimal(row.getValue("amount")).compareTo(BigDecimal("1000")))
        assertEquals(0, BigDecimal(row.getValue("fee")).compareTo(BigDecimal("1.5")))
        assertEquals(0, BigDecimal(row.getValue("rate")).compareTo(BigDecimal("0.0015")))
        assertEquals("Standard fee rate of 0.15%", row["description"])
        assertEquals("2023-08-30 15:42:17.610059", row["created_at"], "created_at must not shift under the UTC+14 JVM")
        assertEquals("SETTLED", row["state"])
        assertEquals(BRIEF_FINGERPRINT, row["fingerprint"])

        // One charge: FeeCharge holds exactly the charge the record names.
        val charge = checkNotNull(Observations.charge("txn_001")) { "FeeCharge holds no charge for txn_001" }
        assertEquals(charge.chargeId, row["charge_id"])
        assertEquals(charge.chargedAt, Observations.timestamp(row.getValue("charged_at")))
        assertEquals(BigDecimal("1.50"), charge.fee)
        assertEquals("USD", charge.asset)
        assertEquals(BRIEF_FINGERPRINT, charge.fingerprint)
    }

    @Test
    fun `actuator health is UP`() {
        val response = Http.get("http://localhost:$port/actuator/health")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\":\"UP\"")) { response.body() }
    }

    @Test
    fun `FeeCharge is not reachable through the ingress, and a real request still charges once`() {
        val direct = Http.postJson(
            "${TestInfrastructure.ingressUrl}/FeeCharge/txn_x/charge",
            """{"fee":"0.01","asset":"USD","fingerprint":"$BRIEF_FINGERPRINT"}""",
        )

        assertFalse(direct.statusCode() in 200..299) { "a direct ingress call to FeeCharge must fail: ${direct.statusCode()} ${direct.body()}" }
        assertFalse(TestInfrastructure.admin.service("FeeCharge").get("public").asBoolean())
        assertNull(Observations.charge("txn_x"), "the refused call must not have charged")

        val response = postFee(BRIEF_REQUEST.replace("\"txn_001\"", "\"txn_x\""))

        assertEquals(200, response.statusCode(), response.body())
        assertRendered(response.body(), "fee", "1.5")
        val charge = checkNotNull(Observations.charge("txn_x")) { "FeeCharge holds no charge for txn_x" }
        assertEquals(BigDecimal("1.50"), charge.fee)
        assertEquals(charge.chargeId, Observations.feeRecords(jdbc, "txn_x").single()["charge_id"])
    }

    /**
     * A resend once the fee is recorded is decided by the stored record's fingerprint (D4): the same
     * payload gets the first body, a different one never gets the first one's result.
     * `idempotency.feature` covers every other duplicate case (AC16–AC19).
     */
    @Test
    fun `a resend gets the same body for the same payload, and 409 with nothing changed for another`() {
        val first = postFee(BRIEF_REQUEST.replace("\"txn_001\"", "\"txn_resend\""))
        val charge = checkNotNull(Observations.charge("txn_resend")) { "FeeCharge holds no charge for txn_resend" }

        val same = postFee(BRIEF_REQUEST.replace("\"txn_001\"", "\"txn_resend\"").replace("\"amount\": 1000", "\"amount\": 1000.00"))
        val other = postFee(BRIEF_REQUEST.replace("\"txn_001\"", "\"txn_resend\"").replace("\"amount\": 1000", "\"amount\": 2000"))

        assertEquals(200, first.statusCode(), first.body())
        assertEquals(200, same.statusCode(), same.body())
        assertEquals(first.body(), same.body())
        assertEquals(409, other.statusCode(), other.body())
        assertEquals("application/problem+json", other.headers().firstValue("content-type").orElse(null))
        val problem = json.readTree(other.body())
        assertEquals("/problems/payload-conflict", problem.get("type").asString())
        assertEquals("this transaction_id was already used with a different payload", problem.get("detail").asString())
        assertEquals(charge, Observations.charge("txn_resend"), "one charge, unchanged")
        assertEquals(1, Observations.feeRecords(jdbc, "txn_resend").size)
        assertEquals(0, BigDecimal(Observations.feeRecords(jdbc, "txn_resend").single().getValue("amount")).compareTo(BigDecimal("1000")))
    }

    /**
     * The 400 body as it goes over the wire: `application/problem+json` with a top-level `errors`
     * array (AC27's shape, which relies on Jackson flattening the problem's properties). An invalid
     * amount still lets the asset type's rule violation be listed.
     */
    @Test
    fun `an invalid body is a 400 problem with a top-level errors array, and nothing is charged or recorded`() {
        val response = postFee(
            BRIEF_REQUEST.replace("\"txn_001\"", "\"txn_invalid\"")
                .replace("\"amount\": 1000", "\"amount\": \"1000\"")
                .replace("\"FIAT\"", "\"CRYPTO\""),
        )

        assertEquals(400, response.statusCode(), response.body())
        assertEquals("application/problem+json", response.headers().firstValue("content-type").orElse(null))
        val problem = json.readTree(response.body())
        assertEquals("/problems/invalid-request", problem.get("type").asString())
        assertEquals(400, problem.get("status").asInt())
        val errors = problem.get("errors")
        assertTrue(errors != null && errors.isArray) { "no top-level errors array: ${response.body()}" }
        assertEquals(
            listOf("amount" to "must be a JSON number", "asset_type" to "USD is FIAT"),
            errors.values().map { it.path("field").asString(null) to it.path("message").asString(null) },
            response.body(),
        )
        assertNull(Observations.charge("txn_invalid"), "an invalid request must not charge")
        assertEquals(0, Observations.feeRecords(jdbc, "txn_invalid").size)
    }

    private fun assertRendered(body: String, key: String, rendered: String) {
        assertTrue(Regex("\"$key\":${Regex.escape(rendered)}(?=[,}])").containsMatchIn(body)) {
            "expected \"$key\":$rendered in the raw body: $body"
        }
    }

    private companion object {
        val BRIEF_REQUEST = """
            {
              "transaction_id": "txn_001",
              "amount": 1000,
              "asset": "USD",
              "asset_type": "FIAT",
              "type": "Mobile Top Up",
              "state": "SETTLED - PENDING FEE",
              "created_at": "2023-08-30 15:42:17.610059"
            }
        """.trimIndent()

        val BRIEF_RESPONSE = """
            {
              "transaction_id": "txn_001",
              "amount": 1000,
              "asset": "USD",
              "type": "Mobile Top Up",
              "fee": 1.5,
              "rate": 0.0015,
              "description": "Standard fee rate of 0.15%"
            }
        """.trimIndent()

        /** PayloadFingerprintServiceImplTest pins the same digest for the brief's sample. */
        const val BRIEF_FINGERPRINT = "a0de743b9a60a3bc322ba13619efba691d84e68444823b69f41cf27ea8b09798"
    }
}
