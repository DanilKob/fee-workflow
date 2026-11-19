package com.cashi.fee.service

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure

/**
 * What a failed call means for the caller: plan §3 step 4's failure cases 1–6. Pure: no I/O. The
 * service applies the answer; the API renders it.
 *
 * 1. `send` refused or unresolved: 503 "nothing was started".
 * 2. `send` failed in transport otherwise: 503 "outcome unknown; resending the same request is safe" (Q4).
 * 3. `send` answered with an error status (a duplicate is an outcome, not a failure): a retryable 503.
 * 4. `status` failed, in POST or GET: 503.
 * 5. Waiting failed in transport: 202 once this payload is known to be the run's (an accepted send
 *    or a matched fingerprint); otherwise the status is read again, since a payload that hasn't been
 *    checked never gets a 202.
 * 6. The fee record lookup failed: 503 "fee records unavailable" ([recordLookupFailed]).
 *
 * A run that ended with an untagged failure (a status while waiting) is no transport failure: it is
 * a logged 500 (plan §3 step 6).
 */
interface GatewayFailureMappingService {

    /**
     * Cases 1–5: [failure] of the call made in [phase]. [payloadChecked] says whether this payload is
     * known to be the run's (its send was accepted, or the run's fingerprint matched); it matters
     * only for a failed wait. A failure reported in [GatewayPhase.RECORD_LOOKUP] is case 6.
     */
    fun outcomeOf(phase: GatewayPhase, failure: GatewayFailure, payloadChecked: Boolean): Outcome

    /** Case 6: the fee records could not be read. It never falls through to the workflow's status. */
    fun recordLookupFailed(): Outcome.Unavailable

    /** What the caller gets for a failed call. */
    sealed interface Outcome {

        /** A 503 with this wording and its `Retry-After`. */
        data class Unavailable(val unavailability: Unavailability) : Outcome

        /** A 202: the run has this payload and may still be going (AC28). */
        data object Processing : Outcome

        /** Read the run's status again before answering (case 5, payload not yet checked). */
        data object RereadStatus : Outcome

        /** A logged 500: the run ended with a failure that carries no rejection tag. */
        data object RunFailed : Outcome
    }
}
