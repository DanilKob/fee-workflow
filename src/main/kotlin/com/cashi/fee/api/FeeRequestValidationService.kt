package com.cashi.fee.api

import com.cashi.fee.domain.Transaction

/**
 * Turns a raw [TransactionFeeRequest] into a [Transaction], or fails with a
 * [RequestValidationException] that lists every invalid field at once.
 *
 * The bounds on id and amount also protect `fee_record`. A value past them couldn't be stored,
 * which would leave a charge with no record.
 */
interface FeeRequestValidationService {

    /** The validated transaction, or a [RequestValidationException] with every invalid field. */
    fun validate(request: TransactionFeeRequest): Transaction

    /**
     * Checks the `transaction_id` from the `GET /transaction/{id}/fee` path against the body's
     * pattern, before anything is queried.
     */
    fun validateId(transactionId: String): String
}
