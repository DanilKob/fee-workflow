package com.cashi.fee.service

import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.Transaction
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.RunStatus
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.GatewayFailureMappingService.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Fingerprints the transaction, starts its workflow run and waits for the run's output for up to
 * `fees.sync-wait`; and looks a fee up for GET. The fingerprint comes from [PayloadFingerprintService],
 * and every failed call goes through [GatewayFailureMappingService] (plan §3 step 4, cases 1–6).
 *
 * A send that finds a run already there is a duplicate (plan §3 step 4, D7): a stored record
 * decides first (D4), then the run's status, read before anything waits. Only a payload known to
 * be the run's waits as its own; a run that hasn't stored a fingerprint yet is waited for, and the
 * payload is checked afterwards. A payload that hasn't been checked never gets a 202: at the
 * deadline, or after a transport error while waiting, the run's status is read again (step 4.3).
 *
 * The record lookup is blocking JDBC, so it runs under [Dispatchers.IO] (KS-D5); every `catch`
 * here rethrows a [CancellationException] first (KS-R2).
 */
@Service
class FeeServiceImpl(
    private val gateway: FeeWorkflowGateway,
    private val records: FeeRecordRepository,
    private val fingerprints: PayloadFingerprintService,
    private val failureMapping: GatewayFailureMappingService,
    properties: FeeProperties,
) : FeeService {

    private val syncWait: Duration = properties.syncWait.toKotlinDuration()

    override suspend fun chargeFee(transaction: Transaction): FeeChargeResult {
        val fingerprint = fingerprints.of(transaction)
        val transactionId = transaction.transactionId

        return when (val sent = gateway.send(transaction, fingerprint)) {
            SendResult.Accepted -> awaitOwnRun(transactionId, fingerprint)
            SendResult.Duplicate -> onDuplicate(transactionId, fingerprint)
            is SendResult.Failed -> unavailableOrFailed(GatewayPhase.SEND, sent.failure)
        }
    }

    override suspend fun lookUpFee(transactionId: String): FeeChargeResult {
        val record = when (val lookup = findRecord(transactionId)) {
            is RecordLookup.Found -> lookup.record
            RecordLookup.Unavailable -> return recordsUnavailable()
        }
        if (record != null) return FeeChargeResult.Completed(record.toResult())

        return withStatus(transactionId) { run ->
            val rejection = run.rejection
            when {
                rejection != null -> rejected(rejection)
                run.fingerprint != null -> FeeChargeResult.Processing
                else -> FeeChargeResult.NotFound
            }
        }
    }

    /**
     * This payload is the run's (its send was accepted, or the run stored its fingerprint), so the
     * run's rejection is this payload's, and a run still going at the deadline is a 202 (AC28).
     */
    private suspend fun awaitOwnRun(transactionId: String, fingerprint: String): FeeChargeResult =
        when (val output = gateway.awaitOutput(transactionId, syncWait)) {
            is OutputResult.Ready -> outcomeOf(output, fingerprint)
            is OutputResult.Rejected -> FeeChargeResult.Rejected(output.reason, output.detail)
            OutputResult.Pending -> FeeChargeResult.Processing
            is OutputResult.Failed -> unavailableOrFailed(GatewayPhase.AWAIT_OUTPUT, output.failure, payloadChecked = true)
        }

    /**
     * A run for this id already exists. A stored record decides by its fingerprint (D4). With none,
     * the run's status decides before anything waits: another payload's fingerprint is a conflict
     * and this payload's stored rejection is that rejection (Q8), both at once; this payload's
     * fingerprint alone means the run is this payload's, so wait for it; no fingerprint yet means
     * the run hasn't reached its first step, so wait and check the payload afterwards.
     */
    private suspend fun onDuplicate(transactionId: String, fingerprint: String): FeeChargeResult {
        val record = when (val lookup = findRecord(transactionId)) {
            is RecordLookup.Found -> lookup.record
            RecordLookup.Unavailable -> return recordsUnavailable()
        }
        if (record != null) {
            return if (record.fingerprint == fingerprint) FeeChargeResult.Completed(record.toResult()) else payloadConflict()
        }

        return withStatus(transactionId) { run ->
            decideByStatus(
                run,
                fingerprint,
                ownRun = { awaitOwnRun(transactionId, fingerprint) },
                noFingerprint = { awaitUnchecked(transactionId, fingerprint) },
            )
        }
    }

    /**
     * The run had stored no fingerprint when the duplicate arrived, so this payload is unchecked: a
     * result counts only with this payload's fingerprint, and every other end of the wait is
     * decided by the status read again (plan §3 step 4.3).
     */
    private suspend fun awaitUnchecked(transactionId: String, fingerprint: String): FeeChargeResult =
        when (val output = gateway.awaitOutput(transactionId, syncWait)) {
            is OutputResult.Ready -> outcomeOf(output, fingerprint)
            is OutputResult.Rejected -> recheckStatus(transactionId, fingerprint, ended = output)
            OutputResult.Pending -> recheckStatus(transactionId, fingerprint)
            is OutputResult.Failed ->
                when (val outcome = failureMapping.outcomeOf(GatewayPhase.AWAIT_OUTPUT, output.failure, payloadChecked = false)) {
                    Outcome.RereadStatus -> recheckStatus(transactionId, fingerprint)
                    else -> resultOf(GatewayPhase.AWAIT_OUTPUT, output.failure, outcome)
                }
        }

    /**
     * The wait for an unchecked payload ended without a result: at the deadline, after a transport
     * error, or with the run's rejection ([ended]). The status read again decides as before the
     * wait; this payload's run is its rejection or still going (a 202), and a run that still has
     * no fingerprint hasn't started yet. A rejection is passed on only once the run is known to
     * have this payload, since a rejection of another payload is the one wrong answer.
     */
    private suspend fun recheckStatus(transactionId: String, fingerprint: String, ended: OutputResult.Rejected? = null): FeeChargeResult =
        withStatus(transactionId) { run ->
            decideByStatus(
                run,
                fingerprint,
                ownRun = { ended?.let { FeeChargeResult.Rejected(it.reason, it.detail) } ?: FeeChargeResult.Processing },
                noFingerprint = { FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.NOT_STARTED_YET) },
            )
        }

    /**
     * What the run's stored status says of this payload: another payload's fingerprint is a
     * conflict; this payload's stored rejection is that rejection (Q8); otherwise [ownRun] when the
     * run has this payload's fingerprint, and [noFingerprint] when it has none yet.
     */
    private inline fun decideByStatus(
        run: RunStatus,
        fingerprint: String,
        ownRun: () -> FeeChargeResult,
        noFingerprint: () -> FeeChargeResult,
    ): FeeChargeResult =
        when (run.fingerprint) {
            null -> noFingerprint()
            fingerprint -> run.rejection?.let(::rejected) ?: ownRun()
            else -> payloadConflict()
        }

    /** The run's status, read once and handed to [decide]; an unreadable status is a 503 (case 4). */
    private suspend inline fun withStatus(transactionId: String, decide: (RunStatus) -> FeeChargeResult): FeeChargeResult =
        when (val status = gateway.status(transactionId)) {
            is StatusResult.Known -> decide(status.status)
            is StatusResult.Failed -> unavailableOrFailed(GatewayPhase.STATUS, status.failure)
        }

    /** The run's result. A result from a run started with another payload is a conflict, never a 200. */
    private fun outcomeOf(output: OutputResult.Ready, fingerprint: String): FeeChargeResult =
        if (output.fingerprint == fingerprint) FeeChargeResult.Completed(output.result) else payloadConflict()

    /** A failed call that can't need a status re-read: every phase but a wait with an unchecked payload. */
    private fun unavailableOrFailed(phase: GatewayPhase, failure: GatewayFailure, payloadChecked: Boolean = true): FeeChargeResult =
        resultOf(phase, failure, failureMapping.outcomeOf(phase, failure, payloadChecked))

    private fun resultOf(phase: GatewayPhase, failure: GatewayFailure, outcome: Outcome): FeeChargeResult =
        when (outcome) {
            is Outcome.Unavailable -> FeeChargeResult.Unavailable(phase, outcome.unavailability)
            Outcome.Processing -> FeeChargeResult.Processing
            Outcome.RunFailed -> FeeChargeResult.Failed(phase, failure)
            Outcome.RereadStatus -> error("a $phase failure with an unchecked payload needs the status read again")
        }

    /**
     * The stored record for [transactionId], or [RecordLookup.Unavailable] when the store can't be
     * read (case 6): a data access failure, or a transaction that couldn't start because no
     * connection could be had. The cause is logged here, once; the caller never sees it (O1).
     */
    private suspend fun findRecord(transactionId: String): RecordLookup =
        try {
            RecordLookup.Found(withContext(Dispatchers.IO) { records.find(transactionId) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DataAccessException) {
            recordLookupFailed(transactionId, failure)
        } catch (failure: TransactionException) {
            recordLookupFailed(transactionId, failure)
        }

    private fun recordLookupFailed(transactionId: String, failure: Exception): RecordLookup {
        log.warn("fee record lookup failed: transaction_id={} phase={}", transactionId, GatewayPhase.RECORD_LOOKUP, failure)
        return RecordLookup.Unavailable
    }

    private fun recordsUnavailable(): FeeChargeResult =
        FeeChargeResult.Unavailable(GatewayPhase.RECORD_LOOKUP, failureMapping.recordLookupFailed().unavailability)

    private fun payloadConflict(): FeeChargeResult =
        FeeChargeResult.Rejected(RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL)

    private fun rejected(rejection: RunRejection): FeeChargeResult = FeeChargeResult.Rejected(rejection.reason, rejection.detail)

    /** What the record lookup found: a record or none, or a store that can't be read. */
    private sealed interface RecordLookup {
        data class Found(val record: FeeRecord?) : RecordLookup

        data object Unavailable : RecordLookup
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FeeServiceImpl::class.java)
    }
}
