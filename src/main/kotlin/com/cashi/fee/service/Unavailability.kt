package com.cashi.fee.service

/**
 * Why a fee request gets a 503, and what the caller may assume. Each [detail] is fixed wording that
 * never echoes a cause.
 *
 * Only a refused connection says "nothing was started". Any other failure may have reached the
 * workflow. Resending is still safe, because the workflow is keyed by `transaction_id` and charges
 * once.
 */
enum class Unavailability(val detail: String, val retryAfterSeconds: Int) {

    /** The connection to Restate was refused, or its host did not resolve. */
    NOTHING_STARTED("Restate unreachable; nothing was started; resend the same request", 5),

    /** The send failed in transport, possibly after it reached Restate. */
    OUTCOME_UNKNOWN("outcome unknown; resending the same request is safe", 5),

    /** Restate answered the send with an error status other than a duplicate. */
    SEND_REFUSED("Restate refused the request; resending the same request is safe", 5),

    /** The workflow's status could not be read. */
    STATUS_UNAVAILABLE("fee workflow status unavailable; resending the same request is safe", 5),

    /** The fee records could not be read. */
    RECORDS_UNAVAILABLE("fee records unavailable; resending the same request is safe", 5),

    /**
     * A duplicate whose run still has no fingerprint after the wait: the run was accepted but
     * hasn't reached its first step.
     */
    NOT_STARTED_YET("not started yet; retry with the same payload", 1),
}
