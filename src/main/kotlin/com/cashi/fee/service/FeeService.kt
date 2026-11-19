package com.cashi.fee.service

import com.cashi.fee.domain.Transaction

/** Charges the fee for a validated transaction, exactly once per `transaction_id` (D1, D2), and looks it up. */
interface FeeService {

    /** `POST /transaction/fee`: starts or joins the run for the transaction, waiting up to `fees.sync-wait`. */
    suspend fun chargeFee(transaction: Transaction): FeeChargeResult

    /**
     * `GET /transaction/{id}/fee` (AC29): the stored record, else the run's stored rejection, else a
     * run still going, else nothing. [transactionId] has passed the id pattern.
     */
    suspend fun lookUpFee(transactionId: String): FeeChargeResult
}
