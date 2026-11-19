package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A valid fee request: every field present and in range, the asset known and matching its asset
 * type, and the type registered and allowing it. [createdAt] is a UTC local date-time.
 *
 * Only [TransactionFactory] builds one. The constructor and `copy` are internal, and
 * `ArchitectureTest` checks that nothing outside `domain` calls them, so no transaction skips the
 * checks.
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
    /** The amount's asset code. */
    val asset: String get() = amount.asset.code

    companion object {
        /** The only input state eligible for a fee. */
        const val ELIGIBLE_STATE: String = "SETTLED - PENDING FEE"

        /**
         * 1 to 64 of A-Z, a-z, 0-9, `_` and `-`, to fit the `varchar(64)` key of `fee_record`.
         * Every id check, in a body, a path or a workflow input, goes through [checkId].
         */
        val ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,64}")

        /** The fixed text that says what [ID_PATTERN] accepts. */
        const val ID_RULE: String = "must be 1 to 64 characters of A-Z, a-z, 0-9, _ or -"

        /**
         * Why [transactionId] breaks the id rule, or null if it fits. A blank id counts as missing.
         * Used for a body's id and for the id in the `GET /transaction/{id}/fee` path.
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
