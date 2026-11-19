package com.cashi.fee.domain

/**
 * The SHA-256 fingerprint that tells "the same payload" from "a different payload" under one
 * `transaction_id` (AC18): lowercase hex over the normalised fields, joined with the unit
 * separator `\u001F`. The amount is compared numerically, so `1000` and `1000.00` match.
 *
 * A compatibility contract: every stored record, workflow state and FeeCharge holds a fingerprint,
 * so a changed digest would turn every resend of an old payload into a 409.
 */
interface PayloadFingerprintService {

    /** The fingerprint of [transaction]'s payload; its `transaction_id` is not part of it. */
    fun of(transaction: Transaction): String
}
