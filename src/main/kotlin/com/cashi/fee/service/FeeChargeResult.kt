package com.cashi.fee.service

import com.cashi.fee.domain.FeeResult
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure

/** What [FeeService] answers: the fee charged for one `transaction_id`, or why there is none (yet). */
sealed interface FeeChargeResult {

    /** The fee was charged and recorded, now or by an earlier run with the same payload. */
    data class Completed(val result: FeeResult) : FeeChargeResult

    /** The transaction was refused: a payload conflict, or the fee rules after the run started. */
    data class Rejected(val reason: RejectionReason, val detail: String) : FeeChargeResult

    /** The run has this payload and is still going: the caller polls the status link (AC28). */
    data object Processing : FeeChargeResult

    /** Restate or the fee records can't answer now (503); [unavailability] says what the caller may assume. */
    data class Unavailable(val phase: GatewayPhase, val unavailability: Unavailability) : FeeChargeResult

    /** The run ended with a failure that carries no rejection tag (a logged 500); [failure] says how. */
    data class Failed(val phase: GatewayPhase, val failure: GatewayFailure) : FeeChargeResult

    /** Lookup only: nothing is known for this `transaction_id` (404). */
    data object NotFound : FeeChargeResult
}
