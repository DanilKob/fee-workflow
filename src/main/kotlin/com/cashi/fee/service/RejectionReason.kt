package com.cashi.fee.service

/**
 * Why the fee workflow refused a transaction, as its terminal failures are tagged. Restate's own
 * cancel and kill also end with code 409, so the tag decides, never the status code alone.
 *
 * Never change a [wire] tag: paused invocations and stored workflow state already hold them.
 * [httpStatus] is carried by both the terminal failure and the API's problem.
 */
enum class RejectionReason(val wire: String, val httpStatus: Int) {

    /** The `transaction_id` was already used with a different payload. */
    PAYLOAD_CONFLICT("payload_conflict", 409),

    /** The fee rules refused the transaction after the workflow started. */
    RULE_REJECTED("rule_rejected", 400),
    ;

    companion object {

        /** The fixed detail of every payload conflict. */
        const val PAYLOAD_CONFLICT_DETAIL: String = "this transaction_id was already used with a different payload"

        /** The reason a wire tag names; null for an absent or unknown tag. */
        fun fromWire(wire: String?): RejectionReason? = entries.firstOrNull { it.wire == wire }
    }
}
