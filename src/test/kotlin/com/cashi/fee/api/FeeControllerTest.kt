package com.cashi.fee.api

import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.DefaultAssetRegistry
import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeCalculationServiceImpl
import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.FeeResult
import com.cashi.fee.domain.PayloadFingerprintServiceImpl
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionFactoryImpl
import com.cashi.fee.service.FeeProperties
import com.cashi.fee.service.FeeServiceImpl
import com.cashi.fee.service.FeeWorkflowGateway
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.RunStatus
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.GatewayFailureMappingServiceImpl
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.support.TestTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration as KotlinDuration

/**
 * The HTTP edges through standalone MockMvc: the real controller, service, validation, exception
 * handler and JSON mapping, over a scripted gateway and record store.
 * One test swaps in a stub validation service, to show the controller uses the injected one.
 */
class FeeControllerTest {

    private val assets = DefaultAssetRegistry()
    private val calculation = FeeCalculationServiceImpl(assets, DefaultFeeSchedule())
    private val validation: FeeRequestValidationService = FeeRequestValidationServiceImpl(TransactionFactoryImpl(assets, calculation), calculation, assets)
    private val json: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    private val result = FeeResult("txn_001", BigDecimal("1000"), "USD", "Mobile Top Up", BigDecimal("1.50"), BigDecimal("0.0015"), "Standard fee rate of 0.15%")

    private fun mvc(gateway: FakeGateway, records: FakeRecords = FakeRecords(), validation: FeeRequestValidationService = this.validation): MockMvc =
        MockMvcBuilders.standaloneSetup(
            FeeController(
                validation,
                FeeServiceImpl(gateway, records, PayloadFingerprintServiceImpl(), GatewayFailureMappingServiceImpl(), FeeProperties(Duration.ofSeconds(1))),
            ),
        )
            .setControllerAdvice(ApiExceptionHandler())
            .setMessageConverters(JacksonJsonHttpMessageConverter(JacksonConfig.configure(JsonMapper.builder().findAndAddModules())))
            .build()

    /** Performs the request, and its async dispatch when the suspend endpoint started one. */
    private fun MockMvc.exchange(request: RequestBuilder): MvcResult {
        val first = perform(request).andReturn()
        return if (first.request.isAsyncStarted) perform(asyncDispatch(first)).andReturn() else first
    }

    private fun postFee(body: String = BRIEF_REQUEST) =
        post("/transaction/fee").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body)

    private fun getFee(id: String) = get("/transaction/$id/fee").accept(MediaType.APPLICATION_JSON)

    private fun MvcResult.body(): String = response.contentAsString

    private fun MvcResult.problem(): JsonNode = json.readTree(body())

    private fun MvcResult.contentType(): String? = response.contentType

    // --- POST ---

    @Test
    fun `a charged fee is a 200 with the seven fields, and the transaction_id is kept for the log line`() {
        val result = mvc(FakeGateway(send = SendResult.Accepted, output = { OutputResult.Ready(result, it) })).exchange(postFee())

        assertEquals(200, result.response.status, result.body())
        assertEquals("application/json", result.contentType())
        assertEquals(BRIEF_RESPONSE, result.body())
        assertEquals("txn_001", result.request.getAttribute(TRANSACTION_ID_ATTRIBUTE))
    }

    @Test
    fun `a run still going at the deadline is a 202 with a Location, Retry-After and the PROCESSING body (AC28)`() {
        val result = mvc(FakeGateway(send = SendResult.Accepted, output = { OutputResult.Pending })).exchange(postFee())

        assertProcessing(result)
    }

    @Test
    fun `a duplicate whose record lookup fails is a 503 problem with Retry-After, and the status is never read (case 6)`() {
        val gateway = FakeGateway(send = SendResult.Duplicate, status = StatusResult.Known(RunStatus("a".repeat(64))))

        val result = mvc(gateway, FakeRecords(failure = DataAccessResourceFailureException("connection refused"))).exchange(postFee())

        assertUnavailable(result, "fee records unavailable; resending the same request is safe")
        assertEquals(emptyList<String>(), gateway.statusRead)
        assertEquals(0, gateway.awaited)
    }

    // --- POST, a duplicate ---

    @Test
    fun `a duplicate whose run stored another payload's fingerprint is a 409 problem, and nothing waits (AC18)`() {
        val gateway = FakeGateway(send = SendResult.Duplicate, status = StatusResult.Known(RunStatus("f".repeat(64))))

        val result = mvc(gateway).exchange(postFee())

        assertEquals(409, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("/problems/payload-conflict", result.problem().get("type").asString())
        assertEquals(RejectionReason.PAYLOAD_CONFLICT_DETAIL, result.problem().get("detail").asString())
        assertEquals(0, gateway.awaited)
    }

    /** A resend of a payload the run rejected gets the same 4xx problem at once. */
    @Test
    fun `a duplicate whose run stored this payload's rejection is that problem, and nothing waits`() {
        val stored = RunRejection(400, RejectionReason.RULE_REJECTED, "amount: the fee is larger than the amount")
        val gateway = FakeGateway(send = SendResult.Duplicate, status = StatusResult.Known(RunStatus(BRIEF_FINGERPRINT, stored)))

        val result = mvc(gateway).exchange(postFee())

        assertEquals(400, result.response.status, result.body())
        assertEquals("/problems/rule-rejected", result.problem().get("type").asString())
        assertEquals(stored.detail, result.problem().get("detail").asString())
        assertEquals(0, gateway.awaited)
    }

    @Test
    fun `a duplicate of this payload's run that ends rule_rejected is a 400 problem (Q7)`() {
        val detail = "amount: the fee is larger than the amount"
        val gateway = FakeGateway(
            send = SendResult.Duplicate,
            output = { OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, detail) },
            status = StatusResult.Known(RunStatus(BRIEF_FINGERPRINT)),
        )

        val result = mvc(gateway).exchange(postFee())

        assertEquals(400, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("/problems/rule-rejected", result.problem().get("type").asString())
        assertEquals(detail, result.problem().get("detail").asString())
    }

    /** Restate's cancel and kill also answer 409. Without a tag that is a 500, never a conflict. */
    @Test
    fun `a duplicate's run that ended with an untagged 409 (a cancel) is a 500 problem, never a 409`() {
        val gateway = FakeGateway(
            send = SendResult.Duplicate,
            output = { OutputResult.Failed(GatewayFailure.Status(409)) },
            status = StatusResult.Known(RunStatus(BRIEF_FINGERPRINT)),
        )

        val result = mvc(gateway).exchange(postFee())

        assertEquals(500, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("/problems/internal-error", result.problem().get("type").asString())
    }

    @Test
    fun `a duplicate of this payload's run gets the run's 200 body (AC16)`() {
        val gateway = FakeGateway(
            send = SendResult.Duplicate,
            output = { OutputResult.Ready(result, it) },
            status = StatusResult.Known(RunStatus(BRIEF_FINGERPRINT)),
        )

        val response = mvc(gateway).exchange(postFee())

        assertEquals(200, response.response.status, response.body())
        assertEquals(BRIEF_RESPONSE, response.body())
    }

    @Test
    fun `a refused connection to Restate is a 503 that says nothing was started (case 1)`() {
        val result = mvc(FakeGateway(send = SendResult.Failed(GatewayFailure.Unreachable))).exchange(postFee())

        assertUnavailable(result, "Restate unreachable; nothing was started; resend the same request")
    }

    @Test
    fun `any other transport failure of the send is a 503 that says the outcome is unknown (case 2, Q4)`() {
        val result = mvc(FakeGateway(send = SendResult.Failed(GatewayFailure.OutcomeUnknown))).exchange(postFee())

        assertUnavailable(result, "outcome unknown; resending the same request is safe")
    }

    @ParameterizedTest(name = "body: {0}")
    @ValueSource(strings = ["{\"transaction_id\": \"txn_001\", \"amount\": 1000,", "not json", "[]", "null", ""])
    fun `a body that isn't a JSON object is a 400 problem with no field listed, and nothing is sent (AC26)`(body: String) {
        val gateway = FakeGateway()

        val result = mvc(gateway).exchange(postFee(body))

        assertEquals(400, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        val problem = result.problem()
        assertEquals("/problems/invalid-request", problem.get("type").asString())
        assertEquals("The request body is not a valid JSON object.", problem.get("detail").asString())
        assertEquals(0, problem.get("errors").size(), result.body())
        assertEquals(0, gateway.sent)
    }

    @Test
    fun `a field of the wrong JSON type is listed with every other invalid field, and nothing is sent`() {
        val gateway = FakeGateway()
        val body = BRIEF_REQUEST.replace("\"txn_001\"", "123").replace("\"Mobile Top Up\"", "[\"Mobile Top Up\"]").replace("\"FIAT\"", "true")

        val result = mvc(gateway).exchange(postFee(body))

        assertEquals(400, result.response.status, result.body())
        assertEquals(
            listOf("transaction_id" to "must be a JSON string", "asset_type" to "must be a JSON string", "type" to "must be a JSON string"),
            result.problem().get("errors").values().map { it.get("field").asString() to it.get("message").asString() },
        )
        assertEquals(0, gateway.sent)
    }

    // --- GET ---

    @ParameterizedTest(name = "id: {0}")
    @ValueSource(strings = ["txn.001", "txn%20001", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"])
    fun `a lookup with a bad id is a 400 that queries nothing`(id: String) {
        val gateway = FakeGateway()
        val records = FakeRecords()

        val result = mvc(gateway, records).exchange(getFee(id))

        assertEquals(400, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("transaction_id", result.problem().get("errors").single().get("field").asString())
        assertEquals(emptyList<String>(), records.lookedUp)
        assertEquals(emptyList<String>(), gateway.statusRead)
        assertNull(result.request.getAttribute(TRANSACTION_ID_ATTRIBUTE))
    }

    @Test
    fun `a lookup with a record is a 200 with exactly the POST's body (AC29)`() {
        val posted = mvc(FakeGateway(send = SendResult.Accepted, output = { OutputResult.Ready(result, it) })).exchange(postFee())
        val records = FakeRecords(record = record())

        val looked = mvc(FakeGateway(), records).exchange(getFee("txn_001"))

        assertEquals(200, looked.response.status, looked.body())
        assertEquals("application/json", looked.contentType())
        assertEquals(posted.body(), looked.body())
        assertEquals("txn_001", looked.request.getAttribute(TRANSACTION_ID_ATTRIBUTE))
    }

    @Test
    fun `a lookup whose record lookup fails is a 503 problem, and the status is never read (case 6)`() {
        val gateway = FakeGateway(status = StatusResult.Known(RunStatus("a".repeat(64))))

        val result = mvc(gateway, FakeRecords(failure = DataAccessResourceFailureException("connection refused"))).exchange(getFee("txn_001"))

        assertUnavailable(result, "fee records unavailable; resending the same request is safe")
        assertEquals(emptyList<String>(), gateway.statusRead)
    }

    @Test
    fun `a lookup of a rejected run answers the rejection's problem (Q8)`() {
        val conflict = RunRejection(409, RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL)
        val rule = RunRejection(400, RejectionReason.RULE_REJECTED, "type: unknown type")

        val conflicted = mvc(FakeGateway(status = StatusResult.Known(RunStatus("a".repeat(64), conflict)))).exchange(getFee("txn_001"))
        val refused = mvc(FakeGateway(status = StatusResult.Known(RunStatus("a".repeat(64), rule)))).exchange(getFee("txn_001"))

        assertEquals(409, conflicted.response.status)
        assertEquals("application/problem+json", conflicted.contentType())
        assertEquals("/problems/payload-conflict", conflicted.problem().get("type").asString())
        assertEquals(RejectionReason.PAYLOAD_CONFLICT_DETAIL, conflicted.problem().get("detail").asString())
        assertEquals(400, refused.response.status)
        assertEquals("/problems/rule-rejected", refused.problem().get("type").asString())
        assertEquals("type: unknown type", refused.problem().get("detail").asString())
    }

    @Test
    fun `a lookup of a run still going is the same 202 as the POST's (AC29)`() {
        val result = mvc(FakeGateway(status = StatusResult.Known(RunStatus("a".repeat(64))))).exchange(getFee("txn_001"))

        assertProcessing(result)
    }

    @Test
    fun `a lookup of an unknown id is a 404 problem (AC29)`() {
        val result = mvc(FakeGateway(status = StatusResult.Known(RunStatus()))).exchange(getFee("txn_001"))

        assertEquals(404, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("/problems/fee-not-found", result.problem().get("type").asString())
        assertEquals(404, result.problem().get("status").asInt())
    }

    @Test
    fun `a lookup whose status can't be read is a 503 (case 4)`() {
        val result = mvc(FakeGateway(status = StatusResult.Failed(GatewayFailure.Unreachable))).exchange(getFee("txn_001"))

        assertUnavailable(result, "fee workflow status unavailable; resending the same request is safe")
    }

    // --- the injected validation service ---

    @Test
    fun `the controller charges what the injected validation service returns, and answers what it refuses`() {
        val validated = TestTransactions.transaction(transactionId = "txn_stub")
        val stub = object : FeeRequestValidationService {
            override fun validate(request: TransactionFeeRequest): Transaction = validated

            override fun validateId(transactionId: String): String =
                throw RequestValidationException(listOf(InvalidField("transaction_id", "refused by the stub")))
        }

        // The real validation service refuses an empty body; this one lets it through as txn_stub.
        val posted = mvc(FakeGateway(send = SendResult.Accepted, output = { OutputResult.Pending }), validation = stub).exchange(postFee("{}"))
        val looked = mvc(FakeGateway(), validation = stub).exchange(getFee("txn_001"))

        assertEquals(202, posted.response.status, posted.body())
        assertEquals("/transaction/txn_stub/fee", posted.response.getHeader("Location"))
        assertEquals(400, looked.response.status, looked.body())
        assertEquals("refused by the stub", looked.problem().get("errors").single().get("message").asString())
    }

    private fun assertProcessing(result: MvcResult) {
        assertEquals(202, result.response.status, result.body())
        assertEquals("/transaction/txn_001/fee", result.response.getHeader("Location"))
        assertEquals("1", result.response.getHeader("Retry-After"))
        assertEquals("application/json", result.contentType())
        assertEquals("""{"transaction_id":"txn_001","status":"PROCESSING"}""", result.body())
    }

    private fun assertUnavailable(result: MvcResult, detail: String) {
        assertEquals(503, result.response.status, result.body())
        assertEquals("application/problem+json", result.contentType())
        assertEquals("/problems/unavailable", result.problem().get("type").asString())
        assertEquals(detail, result.problem().get("detail").asString())
        assertEquals(503, result.problem().get("status").asInt())
        assertEquals(true, result.response.getHeader("Retry-After")?.toIntOrNull()?.let { it > 0 }, "Retry-After: ${result.response.getHeader("Retry-After")}")
    }

    private fun record() = FeeRecord(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = AssetType.FIAT,
        type = "Mobile Top Up",
        fee = BigDecimal("1.500000000000000000"),
        rate = BigDecimal("0.001500000000000000"),
        description = "Standard fee rate of 0.15%",
        ruleId = "mobile-top-up",
        ruleVersion = 1,
        createdAt = LocalDateTime.of(2023, 8, 30, 15, 42, 17, 610_059_000),
        chargeId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        chargedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0, 1),
        fingerprint = "a".repeat(64),
    )

    /** Answers with the scripted results. An unscripted call fails the request, and the test. */
    private class FakeGateway(
        private val send: SendResult? = null,
        private val output: (fingerprint: String) -> OutputResult = { error("a wait, but no output was scripted") },
        private val status: StatusResult? = null,
    ) : FeeWorkflowGateway {
        var sent = 0
        var awaited = 0
        private var fingerprint: String = ""
        val statusRead = mutableListOf<String>()

        override suspend fun send(transaction: Transaction, fingerprint: String): SendResult {
            sent++
            this.fingerprint = fingerprint
            return send ?: error("a send, but none was scripted")
        }

        override suspend fun awaitOutput(transactionId: String, deadline: KotlinDuration): OutputResult {
            awaited++
            return output(fingerprint)
        }

        override suspend fun status(transactionId: String): StatusResult {
            statusRead += transactionId
            return status ?: error("the status was read, but none was scripted")
        }
    }

    /** A record store whose `find` answers [record] or throws [failure]. */
    private class FakeRecords(private val record: FeeRecord? = null, private val failure: RuntimeException? = null) : FeeRecordRepository {
        val lookedUp = mutableListOf<String>()

        override fun find(transactionId: String): FeeRecord? {
            lookedUp += transactionId
            failure?.let { throw it }
            return record
        }

        override fun save(record: FeeRecord) = error("the API never saves a record")
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

        /** The fingerprint of [BRIEF_REQUEST]'s payload, as the service computes it. */
        val BRIEF_FINGERPRINT: String = PayloadFingerprintServiceImpl().of(TestTransactions.transaction())

        const val BRIEF_RESPONSE =
            """{"transaction_id":"txn_001","amount":1000,"asset":"USD","type":"Mobile Top Up","fee":1.5,"rate":0.0015,"description":"Standard fee rate of 0.15%"}"""
    }
}
