package com.cashi.fee.domain

import java.math.BigDecimal

/**
 * How one transaction type prices its fee. Each kind computes, rates and describes itself, so no
 * `when (type)` exists anywhere else. Rounding happens once, in [FeeCalculationService].
 *
 * A rule checks what it can on its own when it is built. Checks that need the asset registry or the
 * whole schedule run in [FeeScheduleValidationService], at startup.
 */
sealed interface FeeRule {

    /** The unrounded fee for [amount] in [asset]. */
    fun compute(amount: BigDecimal, asset: Asset): BigDecimal

    /** The `rate` the response shows: the percentage, or `0` for a flat or no fee. */
    fun nominalRate(): BigDecimal

    /** The human-readable `description` the response shows. */
    fun describe(asset: Asset): String

    /** A share of the amount: `rate` 0.0015 is 0.15%. */
    data class Percentage(val rate: BigDecimal) : FeeRule {
        init {
            require(rate.signum() >= 0 && rate < BigDecimal.ONE) { "a percentage rate must be in [0, 1), was ${rate.toPlainString()}" }
            // fee_record.rate is numeric(38,18). A longer rate would be rounded silently when
            // stored, so a resend or GET would show a different rate than the first response.
            require(!Money.hasMoreDecimalsThan(rate, Money.MAX_SCALE)) {
                "a percentage rate must have at most ${Money.MAX_SCALE} decimals, was ${rate.toPlainString()}"
            }
        }

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = amount.multiply(rate)

        override fun nominalRate(): BigDecimal = rate

        override fun describe(asset: Asset): String = "Standard fee rate of ${Money.plain(rate.movePointRight(2))}%"
    }

    /**
     * A flat fee per asset code, whatever the amount: "Flat fee of 0.50 USD". An asset with no fee
     * here is refused by [FeeCalculationService] before [compute] or [describe] sees it.
     */
    data class Fixed(val perAsset: Map<String, BigDecimal>) : FeeRule {
        init {
            require(perAsset.isNotEmpty()) { "a fixed fee needs a fee for at least one asset" }
            perAsset.forEach { (code, fee) ->
                require(fee.signum() >= 0) { "the fixed fee for $code must not be negative, was ${fee.toPlainString()}" }
            }
        }

        fun hasFeeFor(asset: Asset): Boolean = asset.code in perAsset

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = feeFor(asset)

        override fun nominalRate(): BigDecimal = BigDecimal.ZERO

        /** Needs no rounding mode: startup validation refused fees past their asset's scale. */
        override fun describe(asset: Asset): String = "Flat fee of ${feeFor(asset).setScale(asset.scale).toPlainString()} ${asset.code}"

        private fun feeFor(asset: Asset): BigDecimal =
            requireNotNull(perAsset[asset.code]) { "no fixed fee is configured for ${asset.code}" }
    }

    /** No fee in any asset. [description] says why: "No fee for charity donations". */
    data class NoFee(val description: String) : FeeRule {
        init {
            require(description.isNotBlank()) { "a no-fee rule needs a description" }
        }

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = BigDecimal.ZERO

        override fun nominalRate(): BigDecimal = BigDecimal.ZERO

        override fun describe(asset: Asset): String = description
    }
}
