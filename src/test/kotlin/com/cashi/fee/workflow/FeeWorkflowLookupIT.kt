package com.cashi.fee.workflow

import com.cashi.fee.service.FeeWorkflowGateway
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.support.FeeIntegrationTest
import com.cashi.fee.support.Observations
import com.cashi.fee.support.TestTransactions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The workflow's `lookup` step: a record already stored for the id decides before anything is
 * priced or charged. The record is planted directly, as a run after workflow retention would find
 * it.
 */
@FeeIntegrationTest
class FeeWorkflowLookupIT {

    @Autowired
    private lateinit var gateway: FeeWorkflowGateway

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun transaction(id: String) = TestTransactions.transaction(transactionId = id)

    private fun plantRecord(id: String, fingerprint: String, chargeId: UUID) {
        jdbc.update(
            """
            INSERT INTO fee_record (transaction_id, amount, asset, asset_type, type, fee, rate, description, rule_id,
                                    rule_version, created_at, charge_id, charged_at, state, fingerprint, recorded_at)
            VALUES (?, 1000, 'USD', 'FIAT', 'Mobile Top Up', 1.50, 0.0015, 'Standard fee rate of 0.15%', 'mobile-top-up',
                    1, '2023-08-30 15:42:17.610059', ?, '2026-01-01 00:00:00', 'SETTLED', ?, '2026-01-01 00:00:01')
            """.trimIndent(),
            id, chargeId, fingerprint,
        )
    }

    @Test
    fun `a stored record with the same fingerprint is the outcome, and nothing is charged`() = runBlocking {
        val id = "txn_lookup_same"
        plantRecord(id, FP_A, UUID.randomUUID())

        assertEquals(SendResult.Accepted, gateway.send(transaction(id), FP_A))
        val ready = assertInstanceOf(OutputResult.Ready::class.java, gateway.awaitOutput(id, 30.seconds))

        assertEquals(0, BigDecimal("1.5").compareTo(ready.result.fee))
        assertEquals("Standard fee rate of 0.15%", ready.result.description)
        assertEquals(FP_A, ready.fingerprint)
        assertNull(Observations.charge(id), "the stored record decided; FeeCharge must not be called")
        assertEquals(listOf(Observations.JournalCommand("Run", "lookup")), Observations.workflowJournal(id))
    }

    @Test
    fun `a stored record with another fingerprint is a payload conflict, and nothing is charged`() = runBlocking {
        val id = "txn_lookup_other"
        plantRecord(id, FP_A, UUID.randomUUID())

        assertEquals(SendResult.Accepted, gateway.send(transaction(id), FP_B))
        val output = gateway.awaitOutput(id, 30.seconds)

        assertEquals(OutputResult.Rejected(409, RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL), output)
        assertNull(Observations.charge(id))
        val status = assertInstanceOf(StatusResult.Known::class.java, gateway.status(id)).status
        assertEquals(RunRejection(409, RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL), status.rejection)
        assertEquals(FP_A, Observations.feeRecords(jdbc, id).single()["fingerprint"], "the record is unchanged")
    }

    private companion object {
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
    }
}
