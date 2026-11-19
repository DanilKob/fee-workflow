package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A valid fee request: every field present and in range, its asset known and agreeing with its
 * asset type, its type registered and allowing that asset type. Labels hold the registry spelling
 * (Q11). [createdAt] is a UTC local date-time with microseconds (D8).
 *
 * Only the domain builds one, through [TransactionFactory] (Q19): the constructor and `copy` are
 * internal, and `ArchitectureTest` checks that nothing outside `domain` calls either, so no other
 * layer can hold a transaction that skipped the checks.
 */
@ConsistentCopyVisibility
data class Transaction internal constructor(
    val transactionId: String,
    val amount: Money,
    val assetType: AssetType,
    val type: String,
    val state: String,
    val createdAt: LocalDateTime,
) {
    /** The asset's code: the amount's own asset. */
    val asset: String get() = amount.asset.code

    companion object {
        /** The only input state eligible for a fee (D-state). */
        const val ELIGIBLE_STATE: String = "SETTLED - PENDING FEE"

        /**
         * What a `transaction_id` may be: 1 to 64 of A-Z, a-z, 0-9, `_` and `-`. The 64 is the
         * `fee_record` key's `varchar(64)`. Every check of an id, in a body, in a path or in a
         * workflow input, goes through [checkId].
         */
        val ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,64}")

        /** The fixed text that says what [ID_PATTERN] accepts. */
        const val ID_RULE: String = "must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -"

        /**
         * The id rule alone, without throwing: null for an id that fits [ID_PATTERN], otherwise why
         * not, on `transaction_id` (blank is missing). [TransactionFactory] runs it on a body's id,
         * and the API on the id in the path of `GET /transaction/{id}/fee`.
         */
        fun checkId(transactionId: String): RuleViolation? =
            when {
                transactionId.isBlank() -> RuleViolation(TransactionField.TRANSACTION_ID, RuleViolation.REQUIRED)
                !ID_PATTERN.matches(transactionId) -> RuleViolation(TransactionField.TRANSACTION_ID, ID_RULE)
                else -> null
            }
    }
}

/** What a 200 says: the fee charged for one transaction. */
data class FeeResult(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val type: String,
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
)
