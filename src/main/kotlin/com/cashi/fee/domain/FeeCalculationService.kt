package com.cashi.fee.domain

import java.math.BigDecimal

/** A priced fee: rounded once, at the asset's scale, with the rule that priced it. */
data class FeeQuote(
    val type: String,
    val fee: Money,
    val rate: BigDecimal,
    val description: String,
    val ruleId: String,
    val ruleVersion: Int,
) {
    /** The asset code. The fee is always in the transaction's own asset. */
    val asset: String get() = fee.asset.code
}

/** What [FeeCalculationService] answers: a quote, or every rule the transaction breaks. */
sealed interface FeeCalculation {
    data class Quoted(val quote: FeeQuote) : FeeCalculation

    data class Rejected(val violations: List<RuleViolation>) : FeeCalculation {
        init {
            require(violations.isNotEmpty()) { "a rejection needs at least one violation" }
        }
    }
}

/**
 * The one owner of the fee rules. [TransactionFactory] runs [checkRules] in the API, before any
 * workflow starts, and again in the workflow's `calculate` step. So a rule redeployed mid-flight
 * can't price what the API refused.
 *
 * [quote] takes [Money], so the amount's bounds hold before any arithmetic and no exponent is
 * expanded. Each field gets at most one violation, so the API never lists a field twice.
 */
interface FeeCalculationService {

    /**
     * Every violation of the rules that need no amount, with the field each is reported on:
     * - the type is registered (`type`), and so is the asset (`asset`);
     * - the asset is of [assetType] (`asset_type`);
     * - the type allows the asset's asset type (`type`);
     * - a fixed-fee type has a fee for the asset (`asset`).
     *
     * A null argument is a field the caller already reported. Rules that need it are skipped and
     * the rest still run, so one invalid field hides no other. The type's rules use the registered
     * asset's own type, so a wrong [assetType] neither hides nor invents a type violation.
     */
    fun checkRules(assetCode: String?, assetType: AssetType?, typeLabel: String?): List<RuleViolation>

    /** [quote] on the transaction's own amount and type. */
    fun quote(transaction: Transaction): FeeCalculation

    /**
     * The fee for [amount] under [typeLabel], rounded once, half-up, at the asset's scale. A fee
     * equal to the amount is charged, and a larger one is refused on `amount`. [checkRules] runs
     * again first, so nothing is priced under a type that can't price it.
     */
    fun quote(amount: Money, typeLabel: String): FeeCalculation
}
