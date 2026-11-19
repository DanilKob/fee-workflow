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
 * Starts the workflow run and waits up to `fees.sync-wait` for its output.
 *
 * If a run already exists, a stored record decides first, then the run's status. An unchecked
 * payload never gets a 202: at the deadline, or after a transport error, the status is read again.
 *
 * The record lookup is blocking JDBC, so it runs under [Dispatchers.IO]. Every `catch` here
 * rethrows a [CancellationException] first, so cancellation is never swallowed.
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
     * This payload is known to be the run's, so the run's rejection is this payload's, and a run
     * still going at the deadline is a 202.
     */
    private suspend fun awaitOwnRun(transactionId: String, fingerprint: String): FeeChargeResult =
        when (val output = gateway.awaitOutput(transactionId, syncWait)) {
            is OutputResult.Ready -> outcomeOf(output, fingerprint)
            is OutputResult.Rejected -> FeeChargeResult.Rejected(output.reason, output.detail)
            OutputResult.Pending -> FeeChargeResult.Processing
            is OutputResult.Failed -> unavailableOrFailed(GatewayPhase.AWAIT_OUTPUT, output.failure, payloadChecked = true)
        }

    /**
     * A run for this id already exists. A stored record decides by its fingerprint. Otherwise the
     * run's status decides before anything waits. With no fingerprint stored yet, the run hasn't
     * reached its first step, so wait and check the payload afterwards.
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
     * The run had no fingerprint when the duplicate arrived, so this payload is unchecked. A result
     * counts only if its fingerprint matches. Any other end of the wait re-reads the status.
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
     * The wait for an unchecked payload ended without a result, or with the run's rejection
     * ([ended]). The re-read status decides. [ended] is passed on only once the run is known to
     * have this payload, because another payload's rejection would be the wrong answer.
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
     * Another payload's fingerprint is a conflict. This payload's stored rejection is returned as
     * it is. Otherwise [ownRun] if the fingerprint matches, or [noFingerprint] if none is stored.
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

    /** The run's status, read once and handed to [decide]. An unreadable status is a 503. */
    private suspend inline fun withStatus(transactionId: String, decide: (RunStatus) -> FeeChargeResult): FeeChargeResult =
        when (val status = gateway.status(transactionId)) {
            is StatusResult.Known -> decide(status.status)
            is StatusResult.Failed -> unavailableOrFailed(GatewayPhase.STATUS, status.failure)
        }

    /** The run's result. A result from a run started with another payload is a conflict, never a 200. */
    private fun outcomeOf(output: OutputResult.Ready, fingerprint: String): FeeChargeResult =
        if (output.fingerprint == fingerprint) FeeChargeResult.Completed(output.result) else payloadConflict()

    /** For a failed call that never needs a status re-read, which is all but an unchecked wait. */
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
     * [RecordLookup.Unavailable] when the store can't be read. `TransactionException` is caught
     * too, because a failed connection surfaces as one. The cause is logged here, never returned.
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

    private sealed interface RecordLookup {
        data class Found(val record: FeeRecord?) : RecordLookup

        data object Unavailable : RecordLookup
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FeeServiceImpl::class.java)
    }
}
