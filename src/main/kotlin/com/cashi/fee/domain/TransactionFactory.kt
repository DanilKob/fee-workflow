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
 * The one way to build a [Transaction]. The API and the workflow's input re-check both use it, so
 * each rule exists once.
 *
 * It checks each field, then the rules between fields:
 * - presence: a missing field (null, or a blank id, asset or type) is `is required`;
 * - the id pattern ([Transaction.checkId]);
 * - the amount: [Money.checkBounds], greater than zero, then [Money.check] once the asset is known;
 * - the asset type's exact spelling, the eligible state, and the strict `created_at`;
 * - the rules that need no amount ([FeeCalculationService.checkRules]).
 *
 * Every violation comes back at once, each field at most once: field checks first, in field order,
 * then the rules. A missing field hides no rule on the others. Pricing (fee vs amount) is checked
 * by [FeeCalculationService.quote], not here.
 */
interface TransactionFactory {

    fun create(input: TransactionInput): TransactionCreation
}
