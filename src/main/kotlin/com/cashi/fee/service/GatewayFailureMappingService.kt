package com.cashi.fee.service

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure

/**
 * What a failed call means for the caller. Pure, with no I/O.
 *
 * - `send` refused, or the host unresolved: 503 "nothing was started".
 * - `send` failed in transport otherwise: 503 "outcome unknown".
 * - `send` answered with an error status: a retryable 503. A duplicate is an outcome, not a failure.
 * - `status` failed, in POST or GET: 503.
 * - Waiting failed in transport: 202 if this payload is known to be the run's. Otherwise the status
 *   is read again, because an unchecked payload never gets a 202.
 * - The fee record lookup failed: 503 "fee records unavailable".
 *
 * An error status while waiting means the run ended with an untagged failure: a logged 500.
 */
interface GatewayFailureMappingService {

    /**
     * [payloadChecked] says whether this payload is known to be the run's: its send was accepted, or
     * the run's fingerprint matched. It matters only for a failed wait.
     */
    fun outcomeOf(phase: GatewayPhase, failure: GatewayFailure, payloadChecked: Boolean): Outcome

    /** The fee records could not be read. This never falls through to the workflow's status. */
    fun recordLookupFailed(): Outcome.Unavailable

    /** What the caller gets for a failed call. */
    sealed interface Outcome {

        /** A 503 with this wording and its `Retry-After`. */
        data class Unavailable(val unavailability: Unavailability) : Outcome

        /** A 202: the run has this payload and may still be going. */
        data object Processing : Outcome

        /** Read the run's status again before answering, because the payload isn't checked yet. */
        data object RereadStatus : Outcome

        /** A logged 500: the run ended with a failure that carries no rejection tag. */
        data object RunFailed : Outcome
    }
}
