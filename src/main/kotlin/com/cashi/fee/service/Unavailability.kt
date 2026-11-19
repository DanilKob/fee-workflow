package com.cashi.fee.service

/**
 * Why a fee request can't be answered now, and what the caller may assume (a 503, AC27). Each
 * [detail] is fixed wording the problem carries as it is; none echoes a cause. [retryAfterSeconds]
 * is the problem's `Retry-After`.
 *
 * Only a refused connection says "nothing was started" (Q4): every other failure may have reached
 * the workflow, and resending the same request is safe because the workflow is keyed by
 * `transaction_id` and charges once.
 */
enum class Unavailability(val detail: String, val retryAfterSeconds: Int) {

    /** Case 1: the connection to Restate was refused, or its host did not resolve. */
    NOTHING_STARTED("Restate unreachable; nothing was started; resend the same request", 5),

    /** Case 2: the send failed in transport after it may have reached Restate (Q4). */
    OUTCOME_UNKNOWN("outcome unknown; resending the same request is safe", 5),

    /** Case 3: Restate answered the send with an error status other than a duplicate. */
    SEND_REFUSED("Restate refused the request; resending the same request is safe", 5),

    /** Case 4: the workflow's status could not be read. */
    STATUS_UNAVAILABLE("fee workflow status unavailable; resending the same request is safe", 5),

    /** Case 6: the fee records could not be read. */
    RECORDS_UNAVAILABLE("fee records unavailable; resending the same request is safe", 5),

    /**
     * A duplicate whose run has stored no fingerprint yet, after the wait: the run was accepted but
     * hasn't reached its first step (plan §3 step 4.3).
     */
    NOT_STARTED_YET("not started yet; retry with the same payload", 1),
}
