package com.cashi.fee.service

import com.cashi.fee.domain.FeeResult
import com.cashi.fee.domain.Transaction
import kotlin.time.Duration

/**
 * The service's view of the fee workflow: send a run, wait for its output, read its status. It
 * speaks service and domain types only; the adapter maps them to and from the workflow's journal
 * types. Every outcome is a value; only a cancellation propagates as an exception.
 */
interface FeeWorkflowGateway {

    /** Starts the workflow for the transaction's `transaction_id`, without waiting for it. */
    suspend fun send(transaction: Transaction, fingerprint: String): SendResult

    /**
     * Polls the workflow's output until it is ready, has failed, or [deadline] has passed
     * ([OutputResult.Pending]). It never attaches, so nothing on the server is held open: the
     * deadline, like cancelling the calling coroutine, stops the polling and nothing else.
     */
    suspend fun awaitOutput(transactionId: String, deadline: Duration): OutputResult

    /** The workflow's stored fingerprint and rejection, through its shared `status()` handler. */
    suspend fun status(transactionId: String): StatusResult

    /** Why a call to the workflow failed, classified for the problem the caller gets. */
    sealed interface GatewayFailure {

        /** The connection was refused or the host did not resolve: nothing was started. */
        data object Unreachable : GatewayFailure

        /** The request failed in transport after it may have reached the workflow: outcome unknown. */
        data object OutcomeUnknown : GatewayFailure

        /** The workflow's host answered with this status, with no tag the gateway recognises. */
        data class Status(val code: Int) : GatewayFailure
    }

    sealed interface SendResult {

        /** A new run was started. */
        data object Accepted : SendResult

        /** A run for this `transaction_id` already exists, possibly with another payload. */
        data object Duplicate : SendResult

        data class Failed(val failure: GatewayFailure) : SendResult
    }

    sealed interface OutputResult {

        /** The run's result, with the fingerprint of the payload that run was started with. */
        data class Ready(val result: FeeResult, val fingerprint: String) : OutputResult

        /** The run ended with a tagged rejection; [status] is the status it ended with. */
        data class Rejected(val status: Int, val reason: RejectionReason, val detail: String) : OutputResult

        /** The output could not be read, or the run ended with an untagged failure. */
        data class Failed(val failure: GatewayFailure) : OutputResult

        /** The deadline passed before the output was ready; the run, if any, keeps going. */
        data object Pending : OutputResult
    }

    sealed interface StatusResult {

        data class Known(val status: RunStatus) : StatusResult

        data class Failed(val failure: GatewayFailure) : StatusResult
    }

    /** What a run has stored so far: its payload fingerprint and its rejection; null when none. */
    data class RunStatus(val fingerprint: String? = null, val rejection: RunRejection? = null)

    /** A pre-charge rejection the run stored, so later reads answer the same problem (Q8). */
    data class RunRejection(val status: Int, val reason: RejectionReason, val detail: String)
}
