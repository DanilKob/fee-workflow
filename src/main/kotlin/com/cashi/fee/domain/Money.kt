package com.cashi.fee.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of one asset (Q18): a transaction's amount or the fee charged on it. Every amount, fee
 * and rate is a [BigDecimal] (AC8).
 *
 * Its invariants hold from construction on: at most [MAX_INTEGER_DIGITS] integer digits, and no
 * more significant decimals than its asset's scale (trailing zeros don't count, Q13). The amount is
 * kept as it was given, scale included: `1000.00` stays `1000.00`, and only [plain] drops the zeros.
 *
 * The bounds match the `numeric(38,18)` money columns of `fee_record` (V1 migration): 18 decimals
 * hold the largest asset scale, and the other 20 digits are the integer room. Change one, change
 * the other.
 *
 * Every bound is read from precision and scale, never from the digits written out, so neither a
 * huge exponent (`1E+20000000`) nor a tiny one (`1E-500000000`) is ever expanded: [check] and the
 * constructor cost the same for those as for `1000`.
 */
data class Money(val amount: BigDecimal, val asset: Asset) : Comparable<Money> {

    init {
        val violation = check(amount, asset)
        require(violation == null) { "not an amount of ${asset.code}: ${violation?.message}" }
    }

    /** The plain-text form: `1000`, `1.5`, `0.0015`, never `1E+3` (AC3). */
    fun plain(): String = plain(amount)

    /** Numerically, so `1000.00` equals `1000` here; only two amounts of the same asset compare. */
    override fun compareTo(other: Money): Int {
        require(asset == other.asset) { "${asset.code} doesn't compare with ${other.asset.code}" }
        return amount.compareTo(other.amount)
    }

    companion object {

        /** Integer digits an amount may have (Q1): the integer room of `numeric(38,18)`. */
        const val MAX_INTEGER_DIGITS: Int = 20

        /**
         * The largest asset scale, and the decimals a stored money value keeps: the scale of
         * `numeric(38,18)`. No amount may carry more significant decimals, whatever its asset.
         */
        const val MAX_SCALE: Int = 18

        /** The number with no trailing zeros, so `1000.00` and `1000` are the same value (AC3, Q10). */
        fun normalized(value: BigDecimal): BigDecimal = value.stripTrailingZeros()

        /** The plain-text form every number is rendered in: `1000`, `1.5`, `0.0015`, never `1E+3` (AC3). */
        fun plain(value: BigDecimal): String = normalized(value).toPlainString()

        /**
         * Digits before the decimal point, read from precision and scale so that a huge exponent
         * such as `1E+20000000` is never expanded. A [Long], so `1E+2147483647` can't overflow into
         * a small number.
         */
        fun integerDigits(value: BigDecimal): Long = value.precision().toLong() - value.scale()

        /** Decimal digits that carry information: trailing zeros don't count (Q13). */
        fun significantScale(value: BigDecimal): Int = normalized(value).scale()

        /**
         * Whether [value] has more significant decimals than [maxScale] (Q13), without expanding a
         * tiny exponent. Precision and scale decide most values: a scale within [maxScale] can't
         * hold too many decimals, and a first significant digit past [maxScale] (`1E-500000000`)
         * always does. Only the rest strip trailing zeros, which costs at most one step per digit of
         * the unscaled value, never one per unit of exponent.
         */
        fun hasMoreDecimalsThan(value: BigDecimal, maxScale: Int): Boolean =
            when {
                value.scale() <= maxScale -> false
                value.signum() == 0 -> false
                value.scale().toLong() - value.precision() >= maxScale -> true
                else -> significantScale(value) > maxScale
            }

        /**
         * The bounds a money value keeps whatever its asset, or null: at most [MAX_INTEGER_DIGITS]
         * integer digits, then at most [MAX_SCALE] significant decimals. The first one [amount]
         * breaks, on `amount`. For an amount whose asset isn't known (yet).
         */
        fun checkBounds(amount: BigDecimal): RuleViolation? =
            when {
                integerDigits(amount) > MAX_INTEGER_DIGITS -> RuleViolation(TransactionField.AMOUNT, INTEGER_DIGITS_RULE)
                hasMoreDecimalsThan(amount, MAX_SCALE) -> RuleViolation(TransactionField.AMOUNT, "must have at most $MAX_SCALE decimal places")
                else -> null
            }

        /**
         * Whether [amount] can be an amount of [asset], without throwing: null when it can, or the
         * first invariant it breaks, on `amount`: at most [MAX_INTEGER_DIGITS] integer digits, then
         * no more significant decimals than the asset's scale (AC12, Q13). The asset's scale is at
         * most [MAX_SCALE], so this covers [checkBounds] too, with the asset's own words.
         */
        fun check(amount: BigDecimal, asset: Asset): RuleViolation? =
            when {
                integerDigits(amount) > MAX_INTEGER_DIGITS -> RuleViolation(TransactionField.AMOUNT, INTEGER_DIGITS_RULE)
                hasMoreDecimalsThan(amount, asset.scale) ->
                    RuleViolation(TransactionField.AMOUNT, "has more decimals than ${asset.code} allows (${asset.scale})")
                else -> null
            }

        /**
         * A raw fee rounded once, `HALF_UP`, at [asset]'s scale (D9): the only rounding a fee gets.
         * [raw] must fit [MAX_INTEGER_DIGITS] once rounded, as every fee a validated schedule
         * computes for a valid amount does.
         */
        fun rounded(raw: BigDecimal, asset: Asset): Money = Money(raw.setScale(asset.scale, RoundingMode.HALF_UP), asset)

        private const val INTEGER_DIGITS_RULE = "must have at most $MAX_INTEGER_DIGITS integer digits"
    }
}
