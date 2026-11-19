package com.cashi.fee.domain

import java.math.BigDecimal

/**
 * A fee request's fields as they arrived, parsed but not checked: a null is a field that is
 * missing. The API fills it from the JSON body, the workflow from its journaled input.
 */
data class TransactionInput(
    val transactionId: String?,
    val amount: BigDecimal?,
    val asset: String?,
    val assetType: String?,
    val type: String?,
    val state: String?,
    val createdAt: String?,
)

/** What [TransactionFactory] answers: a valid transaction, or every reason there is none. */
sealed interface TransactionCreation {
    data class Created(val transaction: Transaction) : TransactionCreation

    data class Invalid(val violations: List<RuleViolation>) : TransactionCreation {
        init {
            require(violations.isNotEmpty()) { "an invalid transaction needs at least one violation" }
        }
    }
}

/**
 * The one way to build a [Transaction] (Q19, the DDD factory): it owns every check a transaction
 * must pass, so each rule exists once, and the API and the workflow's input re-check both ask it.
 *
 * It checks, each field on its own and then the rules between them:
 * - presence: a missing field (null, or a blank id, asset or type) is `is required`;
 * - the id pattern ([Transaction.checkId]);
 * - the amount: [Money.checkBounds], greater than zero, then [Money.check] in its asset once the
 *   asset is known, each read from precision and scale, so no exponent is ever expanded;
 * - the asset type's exact spelling, the eligible state, and the strict `created_at` ([DateFormatter]);
 * - the rules that need no amount ([FeeCalculationService.checkRules]): a known type and asset, the
 *   asset of its asset type (AC11), and a type that allows it (AC15).
 *
 * Every violation comes back at once, each field at most once (AC27): field checks first, in field
 * order, then the rules. A missing field gets exactly one violation, `is required`, and hides no
 * rule on the others. Pricing (fee vs amount, AC13) is not a check here: it is
 * [FeeCalculationService.quote]'s, on the transaction or its amount.
 */
interface TransactionFactory {

    fun create(input: TransactionInput): TransactionCreation
}
