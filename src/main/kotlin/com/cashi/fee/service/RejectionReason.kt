package com.cashi.fee.service

/**
 * Why the fee workflow refused a transaction, as its terminal failures are tagged. Restate's own
 * cancel and kill also end an invocation with code 409, so a 409 is never chosen by status code
 * alone: the tag decides (plan §3 step 6).
 *
 * [wire] is the tag as it travels in the failure's `reason` metadata and in workflow state. It is
 * a compatibility contract with paused invocations and stored state: never change one.
 * [httpStatus] is the status both the terminal failure and the API's problem carry.
 */
enum class RejectionReason(val wire: String, val httpStatus: Int) {

    /** The `transaction_id` was already used with a different payload (D7). */
    PAYLOAD_CONFLICT("payload_conflict", 409),

    /** The fee rules refused the transaction after the workflow started (Q7). */
    RULE_REJECTED("rule_rejected", 400),
    ;

    companion object {

        /** The fixed detail of every payload conflict. */
        const val PAYLOAD_CONFLICT_DETAIL: String = "this transaction_id was already used with a different payload"

        /** The reason a wire tag names; null for an absent or unknown tag. */
        fun fromWire(wire: String?): RejectionReason? = entries.firstOrNull { it.wire == wire }
    }
}
