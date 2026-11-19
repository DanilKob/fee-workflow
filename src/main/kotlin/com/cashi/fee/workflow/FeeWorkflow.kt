package com.cashi.fee.workflow

import com.cashi.fee.domain.FeeCalculation
import com.cashi.fee.domain.FeeCalculationService
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.RuleViolation
import com.cashi.fee.domain.TransactionCreation
import com.cashi.fee.domain.TransactionFactory
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.workflow.model.CalculationJournal
import com.cashi.fee.workflow.model.ChargeRequest
import com.cashi.fee.workflow.model.FeeWorkflowInput
import com.cashi.fee.workflow.model.FeeWorkflowOutput
import com.cashi.fee.workflow.model.LookupJournal
import com.cashi.fee.workflow.model.Rejection
import com.cashi.fee.workflow.model.RejectionMetadata
import com.cashi.fee.workflow.model.WorkflowStatus
import com.cashi.fee.workflow.model.recordJournal
import com.cashi.fee.workflow.model.toDomain
import com.cashi.fee.workflow.model.toJournal
import com.cashi.fee.workflow.model.toOutput
import com.cashi.fee.workflow.model.toTransactionInput
import dev.restate.sdk.annotation.Shared
import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.common.TerminalException
import dev.restate.sdk.kotlin.runBlock
import dev.restate.sdk.kotlin.state
import dev.restate.sdk.kotlin.stateKey
import dev.restate.sdk.kotlin.virtualObject
import dev.restate.sdk.kotlin.workflowKey
import dev.restate.sdk.springboot.RestateComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The fee workflow, one instance per `transaction_id`: lookup, calculate, charge, record.
 *
 * Every step is journaled, so a crash or a retry resumes where it stopped and never charges twice.
 * On replay the code outside the steps runs again and must take the same path. So anything
 * non-deterministic (time, random ids, database calls) runs inside a step or comes from Restate's
 * journaled clock and random.
 *
 * Only the steps before the charge may fail terminally, through [rejectBeforeCharge]. After the
 * charge, a terminal failure would leave a charge with no record. So `record` retries until it
 * succeeds, and Restate pauses the invocation for an operator if it keeps failing.
 *
 * The input is not trusted, because anyone who can reach the Restate ingress can call `run`
 * directly. So `calculate` repeats the API's checks, then checks that the id is the workflow key
 * and the fingerprint matches the payload. A failed check gets a fixed detail, never the input.
 */
@RestateComponent
@Workflow
class FeeWorkflow(
    private val transactions: TransactionFactory,
    private val calculator: FeeCalculationService,
    private val fingerprints: PayloadFingerprintService,
    private val records: FeeRecordRepository,
) {

    @Workflow
    suspend fun run(input: FeeWorkflowInput): FeeWorkflowOutput {
        // The key is fixed for the invocation, so it may be read outside a step.
        val key = workflowKey()

        // 1. The fingerprint first, so status() can tell this payload from another at once.
        state().set(FINGERPRINT, input.fingerprint)

        // 2. A record from an earlier run, after workflow retention, decides. It is read by the key,
        // so an input naming another transaction_id can't return that one's record.
        val existing = runBlock("lookup") {
            LookupJournal(withContext(Dispatchers.IO) { records.find(key) }?.toJournal())
        }.stored
        if (existing != null) {
            if (existing.fingerprint == input.fingerprint) return existing.toOutput()
            val conflict = RejectionReason.PAYLOAD_CONFLICT
            rejectBeforeCharge(conflict.httpStatus, conflict, RejectionReason.PAYLOAD_CONFLICT_DETAIL)
        }

        // 3. The checks and the rule run inside the step, so a replay reuses the journaled fee even
        // if a rule has changed since.
        val quote = when (val calculation = runBlock("calculate") { calculate(input, key) }) {
            is CalculationJournal.Quoted -> calculation.quote
            is CalculationJournal.Rejected ->
                rejectBeforeCharge(RejectionReason.RULE_REJECTED.httpStatus, RejectionReason.RULE_REJECTED, calculation.detail)
        }

        // 4. The charge: a journaled call to the FeeCharge object for this id.
        val charge = try {
            virtualObject<FeeCharge>(input.transactionId)
                .charge(ChargeRequest(quote.fee, input.asset, input.fingerprint))
        } catch (failure: TerminalException) {
            val reason = RejectionMetadata.reasonOf(failure) ?: throw failure
            rejectBeforeCharge(failure.code, reason, RejectionMetadata.detailOf(failure.message))
        }

        // 5. The record. Nothing from here on fails terminally.
        val record = recordJournal(input, quote, charge, recordedAt = restateNowUtc())
        runBlock("record") { withContext(Dispatchers.IO) { records.save(record.toDomain()) } }

        // 6. The 200 body's fields, plus the fingerprint the service compares.
        return record.toOutput()
    }

    /** The stored fingerprint and rejection. A field is null until it is stored. */
    @Shared
    suspend fun status(): WorkflowStatus = WorkflowStatus(state().get(FINGERPRINT), state().get(REJECTION))

    /**
     * The fingerprint is compared last, after the factory has checked the amount. Fingerprinting
     * renders the amount in full, and an unchecked huge exponent must never be expanded.
     */
    private fun calculate(input: FeeWorkflowInput, key: String): CalculationJournal {
        val transaction = when (val creation = transactions.create(input.toTransactionInput())) {
            is TransactionCreation.Created -> creation.transaction
            is TransactionCreation.Invalid -> return CalculationJournal.Rejected(detailOf(creation.violations))
        }
        if (transaction.transactionId != key) return CalculationJournal.Rejected(ID_NOT_KEY)

        val quote = when (val calculation = calculator.quote(transaction)) {
            is FeeCalculation.Quoted -> calculation.quote
            is FeeCalculation.Rejected -> return CalculationJournal.Rejected(detailOf(calculation.violations))
        }
        if (fingerprints.of(transaction) != input.fingerprint) return CalculationJournal.Rejected(FINGERPRINT_MISMATCH)
        return CalculationJournal.Quoted(quote.toJournal())
    }

    /** `field: message` per violation, from fixed texts only, so no input is echoed. */
    private fun detailOf(violations: List<RuleViolation>): String =
        violations.joinToString("; ") { "${it.field.name.lowercase()}: ${it.message}" }

    /**
     * Every pre-charge rejection goes through here. The rejection is stored first, outside any
     * step, so `status()` answers the same problem later. Then the tagged terminal failure ends
     * the invocation.
     */
    private suspend fun rejectBeforeCharge(status: Int, reason: RejectionReason, detail: String): Nothing {
        state().set(REJECTION, Rejection(status, reason.wire, detail))
        throw TerminalException(status, detail, RejectionMetadata.of(reason))
    }

    private companion object {
        val FINGERPRINT = stateKey<String>("fingerprint")
        val REJECTION = stateKey<Rejection>("rejection")

        // Fixed details, so neither echoes the input.
        const val ID_NOT_KEY = "transaction_id: must be the workflow key"
        const val FINGERPRINT_MISMATCH = "fingerprint: does not match the payload"
    }
}
