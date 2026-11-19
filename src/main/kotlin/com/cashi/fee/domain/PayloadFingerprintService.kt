package com.cashi.fee.domain

/**
 * Tells "the same payload" from "a different payload" under one `transaction_id`. The amount is
 * compared as a number, so `1000` and `1000.00` match.
 *
 * Never change the digest. Stored records, workflow state and charges all hold fingerprints, so a
 * new digest would turn every resend of an old payload into a 409.
 */
interface PayloadFingerprintService {

    /** The fingerprint of [transaction]'s payload; its `transaction_id` is not part of it. */
    fun of(transaction: Transaction): String
}
