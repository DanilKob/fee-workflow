package com.cashi.fee.workflow

import com.cashi.fee.api.TransactionFeeResponse
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.Transaction
import com.cashi.fee.service.FeeWorkflowGateway
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.RunStatus
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.service.RejectionReason.Companion.PAYLOAD_CONFLICT_DETAIL
import com.cashi.fee.support.FeeChargeSeeder
import com.cashi.fee.support.FeeIntegrationTest
import com.cashi.fee.support.Http
import com.cashi.fee.support.Observations
import com.cashi.fee.support.SeedCharge
import com.cashi.fee.support.TestInfrastructure
import com.cashi.fee.support.TestTransactions
import com.cashi.fee.workflow.model.FeeWorkflowInput
import com.cashi.fee.workflow.model.toWorkflowInput
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.client.kotlin.response
import dev.restate.client.kotlin.toService
import dev.restate.client.kotlin.toWorkflow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * The Restate behaviours the design relies on, proven against the real server through the real
 * gateway:
 * 1. a `TerminalException`'s `reason` metadata reaches the failure the client reads from the
 *    workflow's output, also when FeeCharge raised it and the workflow passed it on;
 * 2. state set just before a terminal failure stays readable from the shared `status()`;
 * 3. FeeCharge's ingress-private setting is proven in FeeEndToEndIT and fee_calculation.feature;
 * 4. a `run` sent straight to the ingress, past the API, is refused before the charge when its
 *    input breaks what the API guarantees (a forged fingerprint, an id that isn't the key);
 * 5. purging a completed workflow run, as workflow retention would, deletes the workflow's journal
 *    and state and frees the key for a new run, while FeeCharge keeps its charge.
 */
@FeeIntegrationTest
class RestateBehaviourIT {

    @Autowired
    private lateinit var gateway: FeeWorkflowGateway

    @Autowired
    private lateinit var client: Client

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var fingerprints: PayloadFingerprintService

    private val json = JsonMapper.builder().build()

    /** The brief's sample under [id], built by the factory as the API builds it. */
    private fun transaction(id: String): Transaction = TestTransactions.transaction(transactionId = id)

    private fun fingerprintOf(id: String): String = fingerprints.of(transaction(id))

    private suspend fun awaitOutput(id: String): OutputResult = gateway.awaitOutput(id, 30.seconds)

    /** The workflow output's raw failure body, as the ingress returns it. */
    private fun rawOutput(id: String) = Http.get("${TestInfrastructure.ingressUrl}/restate/workflow/FeeWorkflow/$id/output")

    @Test
    fun `a payload_conflict tag raised by FeeCharge crosses into the workflow and reaches the output, and status() keeps the rejection`() =
        runBlocking {
            val id = "txn_behaviour_hop"
            client.toService<FeeChargeSeeder>().request { seed(SeedCharge(id, BigDecimal("1.50"), "USD", FP_A)) }.call()

            assertEquals(SendResult.Accepted, gateway.send(transaction(id), fingerprintOf(id)))
            val output = awaitOutput(id)

            assertEquals(OutputResult.Rejected(409, RejectionReason.PAYLOAD_CONFLICT, PAYLOAD_CONFLICT_DETAIL), output)
            val raw = rawOutput(id)
            assertEquals(409, raw.statusCode(), raw.body())
            val body = json.readTree(raw.body())
            assertEquals(RejectionReason.PAYLOAD_CONFLICT.wire, body.path("metadata").path("reason").asString(), "the tag travels as metadata: ${raw.body()}")
            assertEquals(PAYLOAD_CONFLICT_DETAIL, body.path("message").asString(), "no message prefix is needed: ${raw.body()}")

            val status = assertInstanceOf(StatusResult.Known::class.java, gateway.status(id)).status
            assertEquals(RunStatus(fingerprintOf(id), RunRejection(409, RejectionReason.PAYLOAD_CONFLICT, PAYLOAD_CONFLICT_DETAIL)), status)
        }

    @Test
    fun `FeeCharge's tagged failure crosses a handler-to-handler hop unchanged`() = runBlocking {
        val id = "txn_behaviour_seeded"
        client.toService<FeeChargeSeeder>().request { seed(SeedCharge(id, BigDecimal("1.50"), "USD", FP_A)) }.call()

        // The seeder doesn't catch: FeeCharge's own TerminalException reaches the ingress as is.
        val failure = assertThrows<IngressException> {
            runBlocking { client.toService<FeeChargeSeeder>().request { seed(SeedCharge(id, BigDecimal("1.50"), "USD", FP_B)) }.call() }
        }

        assertEquals(409, failure.statusCode)
        val body = json.readTree(failure.responseBody)
        assertEquals(RejectionReason.PAYLOAD_CONFLICT.wire, body.path("metadata").path("reason").asString(), String(failure.responseBody!!))
        assertEquals(PAYLOAD_CONFLICT_DETAIL, body.path("message").asString())
    }

    @Test
    fun `a rule_rejected tag raised by the workflow reaches the output, and status() keeps the rejection`() = runBlocking {
        val id = "txn_behaviour_rule"

        // A type the schedule doesn't hold: no factory builds this, so it is a copy of a valid one.
        val unknownType = transaction(id).copy(type = "No Such Type")
        assertEquals(SendResult.Accepted, gateway.send(unknownType, fingerprints.of(unknownType)))
        val output = awaitOutput(id)

        val detail = "type: unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation"
        assertEquals(OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, detail), output)
        val status = assertInstanceOf(StatusResult.Known::class.java, gateway.status(id)).status
        assertEquals(RunStatus(fingerprints.of(unknownType), RunRejection(400, RejectionReason.RULE_REJECTED, detail)), status)
    }

    @Test
    fun `a completed run keeps its fingerprint and no rejection, and an unknown id has neither`() = runBlocking {
        val id = "txn_behaviour_done"

        assertEquals(SendResult.Accepted, gateway.send(transaction(id), fingerprintOf(id)))
        val ready = assertInstanceOf(OutputResult.Ready::class.java, awaitOutput(id))

        assertEquals(BigDecimal("1.50"), ready.result.fee)
        assertEquals(fingerprintOf(id), ready.fingerprint)
        assertEquals(StatusResult.Known(RunStatus(fingerprintOf(id), null)), gateway.status(id))
        assertEquals(StatusResult.Known(RunStatus(null, null)), gateway.status("txn_behaviour_never_sent"))
    }

    @Test
    fun `a second send for the same transaction_id is reported as a duplicate, and the output is the first run's`() = runBlocking {
        val id = "txn_behaviour_dup"

        assertEquals(SendResult.Accepted, gateway.send(transaction(id), fingerprintOf(id)))
        assertEquals(SendResult.Duplicate, gateway.send(transaction(id), fingerprintOf(id)))
        assertEquals(SendResult.Duplicate, gateway.send(transaction(id), FP_B))

        assertEquals(fingerprintOf(id), assertInstanceOf(OutputResult.Ready::class.java, awaitOutput(id)).fingerprint)
    }

    @Test
    fun `purging a completed run deletes the workflow's state and frees the key for a new run, and FeeCharge keeps its charge`() = runBlocking {
        val id = "txn_behaviour_purge"
        assertEquals(SendResult.Accepted, gateway.send(transaction(id), fingerprintOf(id)))
        val first = assertInstanceOf(OutputResult.Ready::class.java, awaitOutput(id))
        val charge = checkNotNull(Observations.charge(id)) { "FeeCharge holds no charge for $id" }
        assertEquals(SendResult.Duplicate, gateway.send(transaction(id), fingerprintOf(id)), "before the purge the run still holds the key")

        TestInfrastructure.admin.purgeWorkflow("FeeWorkflow", id)

        assertEquals(StatusResult.Known(RunStatus(null, null)), gateway.status(id), "the workflow's state is gone")
        assertEquals(charge, Observations.charge(id), "FeeCharge keeps its charge")
        assertEquals(SendResult.Accepted, gateway.send(transaction(id), fingerprintOf(id)), "the key is free for a new run")
        val again = assertInstanceOf(OutputResult.Ready::class.java, awaitOutput(id))
        assertEquals(TransactionFeeResponse.from(first.result), TransactionFeeResponse.from(again.result), "the new run answers the stored record")
        assertEquals(first.fingerprint, again.fingerprint)
        assertEquals(listOf(Observations.JournalCommand("Run", "lookup")), Observations.workflowJournal(id), "the old journal is gone")
        assertEquals(charge, Observations.charge(id), "nothing was charged again")
    }

    /** A `run` sent straight to the ingress under [key], past the API and its checks. */
    private suspend fun runDirectly(key: String, input: FeeWorkflowInput) {
        client.toWorkflow<FeeWorkflow>(key).request { run(input) }.send()
    }

    /**
     * The run under [key] ended rule_rejected with [detail], and nothing was charged or recorded
     * under [ids].
     */
    private suspend fun assertRejectedBeforeCharge(key: String, detail: String, vararg ids: String) {
        assertEquals(OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, detail), awaitOutput(key))
        ids.forEach { id ->
            assertNull(Observations.charge(id), "no FeeCharge for $id")
            assertEquals(emptyList<Map<String, String>>(), Observations.feeRecords(jdbc, id), "no fee_record for $id")
        }
    }

    @Test
    fun `a direct run with a forged fingerprint is refused before the charge`() = runBlocking {
        val id = "txn_behaviour_forged"

        runDirectly(id, transaction(id).toWorkflowInput(FP_A))

        assertRejectedBeforeCharge(id, "fingerprint: does not match the payload", id)
    }

    @Test
    fun `a direct run whose transaction_id isn't the workflow key is refused before the charge`() = runBlocking {
        val key = "txn_behaviour_key"
        val other = "txn_behaviour_other"

        runDirectly(key, transaction(other).toWorkflowInput(fingerprintOf(other)))

        assertRejectedBeforeCharge(key, "transaction_id: must be the workflow key", key, other)
    }

    @Test
    fun `a direct run with an id the record can't store, or an ineligible state, is refused before the charge`() = runBlocking {
        val longId = "a".repeat(65)
        val tooLong = transaction("a").copy(transactionId = longId)
        runDirectly(longId, tooLong.toWorkflowInput(fingerprints.of(tooLong)))
        assertRejectedBeforeCharge(longId, "transaction_id: must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -", longId)

        val id = "txn_behaviour_state"
        val settled = transaction(id).copy(state = "SETTLED")
        runDirectly(id, settled.toWorkflowInput(fingerprints.of(settled)))
        assertRejectedBeforeCharge(id, "state: must be SETTLED - PENDING FEE", id)
    }

    @Test
    fun `a direct run with a year the format can't write is refused before the charge, not retried`() = runBlocking {
        listOf(12345, -5000).forEach { year ->
            val id = "txn_behaviour_year_${if (year < 0) "neg" else "big"}"
            val input = transaction(id).toWorkflowInput(FP_A).copy(createdAt = java.time.LocalDateTime.of(year, 1, 1, 0, 0))
            runDirectly(id, input)
            assertRejectedBeforeCharge(id, "created_at: must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS", id)
        }
    }

    private companion object {
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
    }
}
