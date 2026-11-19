package com.cashi.fee.service

import com.cashi.fee.domain.Transaction

/** Charges the fee for a validated transaction once per `transaction_id`, and looks it up. */
interface FeeService {

    /** Starts or joins the run for the transaction, and waits for it up to `fees.sync-wait`. */
    suspend fun chargeFee(transaction: Transaction): FeeChargeResult

    /**
     * The stored record, else the run's stored rejection, else a run still going, else nothing.
     * [transactionId] has already passed the id check.
     */
    suspend fun lookUpFee(transactionId: String): FeeChargeResult
}
