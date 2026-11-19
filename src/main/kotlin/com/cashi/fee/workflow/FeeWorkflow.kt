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
 * The fee workflow, one instance per `transaction_id`: lookup → calculate → charge → record.
 *
 * Every step is journaled, so a crash or a retry resumes where it stopped and never charges twice.
 * Only the steps before the charge may fail terminally, through [rejectBeforeCharge]; nothing
 * after the charge does (D2b, AC23): `record` retries until it succeeds, and Restate pauses the
 * invocation for an operator if it keeps failing.
 *
 * The input is not trusted: the API validated it, but anyone who can reach the Restate ingress can
 * send `run` directly. So `calculate` builds its transaction through [TransactionFactory], the
 * same checks the API's transaction passed (the id pattern, the amount's bounds, the labels and
 * the rules between them, the eligible state, `created_at`), then checks what only the workflow
 * can: the `transaction_id` is the workflow key, and the fingerprint is the payload's. A failed
 * check is a `rule_rejected` with a fixed detail, never the input echoed back.
 *
 * The transaction comes from [TransactionFactory], the price from [FeeCalculationService] and the
 * fingerprint from [PayloadFingerprintService], all injected, the same beans the API uses. Postgres
 * is reached only through the [FeeRecordRepository] port, and only inside a step, under
 * [Dispatchers.IO].
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

        // 2. A record from an earlier run (after workflow retention) decides (AC19). It is looked up
        // by the key, so an input naming another transaction_id can't return that one's record.
        val existing = runBlock("lookup") {
            LookupJournal(withContext(Dispatchers.IO) { records.find(key) }?.toJournal())
        }.stored
        if (existing != null) {
            if (existing.fingerprint == input.fingerprint) return existing.toOutput()
            val conflict = RejectionReason.PAYLOAD_CONFLICT
            rejectBeforeCharge(conflict.httpStatus, conflict, RejectionReason.PAYLOAD_CONFLICT_DETAIL)
        }

        // 3. The input checks and the rule run inside the step, so a replay reuses the journaled
        // outcome and fee (AC22).
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

    /** The payload fingerprint and the stored rejection; null fields when none is stored. */
    @Shared
    suspend fun status(): WorkflowStatus = WorkflowStatus(state().get(FINGERPRINT), state().get(REJECTION))

    /**
     * The factory's checks and the rules, then the key, the price and the fingerprint. The
     * fingerprint is compared only after the factory has passed the amount, since fingerprinting
     * renders the amount in full and an unchecked exponent must never be expanded.
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

    /** `field: message` for each violation, the field in its snake_case wire name: fixed texts, no input echoed. */
    private fun detailOf(violations: List<RuleViolation>): String =
        violations.joinToString("; ") { "${it.field.name.lowercase()}: ${it.message}" }

    /**
     * Every pre-charge rejection goes through here: the rejection is stored first, outside any
     * step, so `status()` answers the same problem later (Q8); then the tagged terminal failure
     * ends the invocation.
     */
    private suspend fun rejectBeforeCharge(status: Int, reason: RejectionReason, detail: String): Nothing {
        state().set(REJECTION, Rejection(status, reason.wire, detail))
        throw TerminalException(status, detail, RejectionMetadata.of(reason))
    }

    private companion object {
        val FINGERPRINT = stateKey<String>("fingerprint")
        val REJECTION = stateKey<Rejection>("rejection")

        // The workflow's own checks' fixed details: neither echoes the input.
        const val ID_NOT_KEY = "transaction_id: must be the workflow key"
        const val FINGERPRINT_MISMATCH = "fingerprint: does not match the payload"
    }
}
