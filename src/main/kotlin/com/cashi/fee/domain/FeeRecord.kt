package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * One `fee_record` row (AC24): the request fields, the priced fee and its rule, the charge, and
 * the payload fingerprint. Every value is required; the state is always [SETTLED] (D3, Q2).
 */
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
    /** The 200 body this record renders, the same for POST, a replay and GET (AC29). */
    fun toResult(): FeeResult = FeeResult(transactionId, amount, asset, type, fee, rate, description)

    companion object {
        /** The state after the fee (D3), the only value `fee_record.state` holds. */
        const val SETTLED: String = "SETTLED"
    }
}
