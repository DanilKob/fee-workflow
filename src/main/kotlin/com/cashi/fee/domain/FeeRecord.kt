package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** One `fee_record` row. Every value is required, and the state is always [SETTLED]. */
data class FeeRecord(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val assetType: AssetType,
    val type: String,
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
    val ruleId: String,
    val ruleVersion: Int,
    val createdAt: LocalDateTime,
    val chargeId: UUID,
    val chargedAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val fingerprint: String,
) {
    /** The 200 body, the same for the first POST, a resend and GET. */
    fun toResult(): FeeResult = FeeResult(transactionId, amount, asset, type, fee, rate, description)

    companion object {
        /** The only value `fee_record.state` holds: the fee has been charged. */
        const val SETTLED: String = "SETTLED"
    }
}
