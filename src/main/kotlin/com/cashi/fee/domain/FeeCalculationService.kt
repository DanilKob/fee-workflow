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
    /** The asset's code: the fee is charged in the transaction's asset (AC12). */
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
 * The one owner of the fee rules (requirements §4). [TransactionFactory] runs [checkRules] on every
 * transaction it builds, for the API before any workflow starts and for the workflow's `calculate`
 * step again, so a rule redeployed mid-flight can't price what the API refused; [quote] then prices.
 *
 * [checkRules] needs no amount, so the API can list every invalid field at once (AC27). [quote]
 * takes an amount that is already [Money], so its bounds hold before any multiply or `setScale`, and
 * no exponent, huge or tiny, is ever expanded. It rounds once, `HALF_UP`, at the asset's scale (D9),
 * and refuses a fee larger than the amount (AC13, D6b).
 *
 * Each field gets at most one violation, so the API never lists a field twice (AC27).
 */
interface FeeCalculationService {

    /**
     * The rules that need no amount, every violation at once; empty when they all pass:
     * - the type is registered (`type`), and the asset is registered (`asset`);
     * - the asset is of [assetType] (`asset_type`, AC11);
     * - the type allows the asset's asset type (`type`, AC15);
     * - a fixed-fee type has a fee for the asset (`asset`, AC15).
     *
     * A null argument is a field the caller has already reported as missing or malformed: the
     * rules that need it are skipped and the others still run, so one invalid field hides no other
     * (AC27). The type's rules read the registered asset's own asset type when the asset is known,
     * so a mismatched [assetType] neither hides nor invents a type violation.
     */
    fun checkRules(assetCode: String?, assetType: AssetType?, typeLabel: String?): List<RuleViolation>

    /** [quote] on the transaction's own amount and type. */
    fun quote(transaction: Transaction): FeeCalculation

    /**
     * The price of [amount] under the type [typeLabel]: the rule's fee, rounded once, `HALF_UP`, at
     * the asset's scale. A fee larger than the amount is refused on `amount` (AC13, D6b); a fee
     * equal to it is charged. The rules of [checkRules] are checked again first, on the amount's own
     * asset, so nothing is ever priced under a type that can't price it.
     */
    fun quote(amount: Money, typeLabel: String): FeeCalculation
}
