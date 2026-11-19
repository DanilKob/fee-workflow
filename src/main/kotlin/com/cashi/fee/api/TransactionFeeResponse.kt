package com.cashi.fee.api

import com.cashi.fee.domain.FeeResult
import com.cashi.fee.domain.Money
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.math.BigDecimal

/**
 * The 200 body: exactly the brief's seven fields, no nulls. Numbers carry no trailing zeros and are
 * written plain, so `1000`, `1.5` and `0.0015` come out exactly.
 */
@JsonPropertyOrder("transactionId", "amount", "asset", "type", "fee", "rate", "description")
data class TransactionFeeResponse(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val type: String,
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
) {
    companion object {
        /** The one way to build a 200 body, so a POST, a resend and a GET render the same bytes. */
        fun from(result: FeeResult): TransactionFeeResponse =
            TransactionFeeResponse(
                transactionId = result.transactionId,
                amount = Money.normalized(result.amount),
                asset = result.asset,
                type = result.type,
                fee = Money.normalized(result.fee),
                rate = Money.normalized(result.rate),
                description = result.description,
            )
    }
}
