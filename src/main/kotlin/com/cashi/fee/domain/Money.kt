package com.cashi.fee.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of one asset: a transaction's amount or the fee charged on it.
 *
 * It never has more than [MAX_INTEGER_DIGITS] integer digits, or more significant decimals than its
 * asset's scale. Trailing zeros don't count. The amount keeps the scale it was given, and only
 * [plain] drops the zeros.
 *
 * The bounds match the `numeric(38,18)` money columns of `fee_record`: 18 decimals for the largest
 * asset scale, 20 digits of integer room. Change one, change the other.
 *
 * Every bound is read from precision and scale, never from the written-out digits. So a huge
 * exponent (`1E+20000000`) or a tiny one (`1E-500000000`) is never expanded, and costs no more to
 * check than `1000`.
 */
data class Money(val amount: BigDecimal, val asset: Asset) : Comparable<Money> {

    init {
        val violation = check(amount, asset)
        require(violation == null) { "not an amount of ${asset.code}: ${violation?.message}" }
    }

    /** The plain-text form: `1000`, `1.5`, `0.0015`, never `1E+3`. */
    fun plain(): String = plain(amount)

    /** Compares numerically, so `1000.00` equals `1000`. Both amounts must be in the same asset. */
    override fun compareTo(other: Money): Int {
        require(asset == other.asset) { "${asset.code} doesn't compare with ${other.asset.code}" }
        return amount.compareTo(other.amount)
    }

    companion object {

        /** The most integer digits an amount may have: the integer room of `numeric(38,18)`. */
        const val MAX_INTEGER_DIGITS: Int = 20

        /**
         * The largest asset scale, and the scale of the `numeric(38,18)` columns. No amount may
         * carry more significant decimals, whatever its asset.
         */
        const val MAX_SCALE: Int = 18

        /** The number without trailing zeros, so `1000.00` and `1000` are the same value. */
        fun normalized(value: BigDecimal): BigDecimal = value.stripTrailingZeros()

        /** The form every number is rendered in: `1000`, `1.5`, `0.0015`, never `1E+3`. */
        fun plain(value: BigDecimal): String = normalized(value).toPlainString()

        /**
         * Digits before the decimal point, read from precision and scale, so a huge exponent such
         * as `1E+20000000` is never expanded. A [Long], so `1E+2147483647` can't overflow.
         */
        fun integerDigits(value: BigDecimal): Long = value.precision().toLong() - value.scale()

        /** Decimal digits that carry information: trailing zeros don't count. */
        fun significantScale(value: BigDecimal): Int = normalized(value).scale()

        /**
         * Whether [value] has more significant decimals than [maxScale], without expanding a tiny
         * exponent. Precision and scale decide most values. Only the rest strip trailing zeros,
         * which costs one step per digit of the unscaled value, never one per unit of exponent.
         */
        fun hasMoreDecimalsThan(value: BigDecimal, maxScale: Int): Boolean =
            when {
                value.scale() <= maxScale -> false
                value.signum() == 0 -> false
                value.scale().toLong() - value.precision() >= maxScale -> true
                else -> significantScale(value) > maxScale
            }

        /**
         * The first asset-free bound [amount] breaks, or null: at most [MAX_INTEGER_DIGITS] integer
         * digits, then at most [MAX_SCALE] significant decimals. For when the asset isn't known.
         */
        fun checkBounds(amount: BigDecimal): RuleViolation? =
            when {
                integerDigits(amount) > MAX_INTEGER_DIGITS -> RuleViolation(TransactionField.AMOUNT, INTEGER_DIGITS_RULE)
                hasMoreDecimalsThan(amount, MAX_SCALE) -> RuleViolation(TransactionField.AMOUNT, "must have at most $MAX_SCALE decimal places")
                else -> null
            }

        /**
         * The first bound [amount] breaks as an amount of [asset], or null: at most
         * [MAX_INTEGER_DIGITS] integer digits, then no more significant decimals than the asset's
         * scale. No scale exceeds [MAX_SCALE], so this covers [checkBounds] too.
         */
        fun check(amount: BigDecimal, asset: Asset): RuleViolation? =
            when {
                integerDigits(amount) > MAX_INTEGER_DIGITS -> RuleViolation(TransactionField.AMOUNT, INTEGER_DIGITS_RULE)
                hasMoreDecimalsThan(amount, asset.scale) ->
                    RuleViolation(TransactionField.AMOUNT, "has more decimals than ${asset.code} allows (${asset.scale})")
                else -> null
            }

        /**
         * [raw] rounded once, half-up, at [asset]'s scale: the only rounding a fee gets. The result
         * must fit [MAX_INTEGER_DIGITS], as every fee from a valid schedule and amount does.
         */
        fun rounded(raw: BigDecimal, asset: Asset): Money = Money(raw.setScale(asset.scale, RoundingMode.HALF_UP), asset)

        private const val INTEGER_DIGITS_RULE = "must have at most $MAX_INTEGER_DIGITS integer digits"
    }
}
