package com.cashi.fee.api

import com.cashi.fee.domain.Transaction

/**
 * Turns a raw [TransactionFeeRequest] into a [Transaction], or fails with a
 * [RequestValidationException] that lists every invalid field at once (AC27).
 *
 * The JSON shape is checked here; every domain check is [com.cashi.fee.domain.TransactionFactory]'s,
 * the one place a [Transaction] is built (Q19). Labels match exactly (Q11). The amount's bounds (its
 * integer digits and its decimals) are read from precision and scale before anything renders or
 * prices it, so no exponent, huge (`1E+20000000`) or tiny (`1E-500000000`), is ever expanded. The
 * bounds on id and amount guard `fee_record` too: it could not store a value past them, which would
 * leave a charge with no record.
 */
interface FeeRequestValidationService {

    /** The validated transaction, or a [RequestValidationException] with every invalid field. */
    fun validate(request: TransactionFeeRequest): Transaction

    /**
     * A `transaction_id` from the path of `GET /transaction/{id}/fee`: the same pattern as the body's,
     * checked before anything is queried. Fails with the one invalid field.
     */
    fun validateId(transactionId: String): String
}
