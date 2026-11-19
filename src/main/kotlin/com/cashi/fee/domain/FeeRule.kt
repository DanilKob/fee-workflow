package com.cashi.fee.domain

import java.math.BigDecimal

/**
 * How one transaction type prices its fee. Each kind computes, rates and describes itself, so no
 * `when (type)` exists anywhere else (requirements §4). Rounding is not a rule's job: it happens
 * once, in [FeeCalculationService].
 *
 * A rule checks what it can check alone when it is built: a percentage in [0, 1), no negative flat
 * fee, a description that isn't blank. What needs the asset registry or the whole schedule (an
 * asset that exists, a flat fee within its asset's scale, unique labels) is
 * [FeeScheduleValidationService]'s job, at startup.
 */
sealed interface FeeRule {

    /** The unrounded fee for [amount] in [asset]. */
    fun compute(amount: BigDecimal, asset: Asset): BigDecimal

    /** The `rate` the response shows: the percentage, or `0` for a flat or no fee (D6a). */
    fun nominalRate(): BigDecimal

    /** The human-readable `description` the response shows. */
    fun describe(asset: Asset): String

    /** A share of the amount: `rate` 0.0015 is 0.15%. */
    data class Percentage(val rate: BigDecimal) : FeeRule {
        init {
            require(rate.signum() >= 0 && rate < BigDecimal.ONE) { "a percentage rate must be in [0, 1), was ${rate.toPlainString()}" }
            // fee_record.rate is numeric(38,18): a longer rate would be rounded silently on store, and a
            // replay or GET would then render a rate other than the first 200 body's.
            require(!Money.hasMoreDecimalsThan(rate, Money.MAX_SCALE)) {
                "a percentage rate must have at most ${Money.MAX_SCALE} decimals, was ${rate.toPlainString()}"
            }
        }

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = amount.multiply(rate)

        override fun nominalRate(): BigDecimal = rate

        override fun describe(asset: Asset): String = "Standard fee rate of ${Money.plain(rate.movePointRight(2))}%"
    }

    /**
     * A flat fee, set per asset: [perAsset] maps an asset code to the fee in that asset, whatever
     * the amount ("Flat fee of 0.50 USD", "Flat fee of 75 JPY"). The description writes the fee at
     * its asset's scale. An asset with no fee here can't be priced: [FeeCalculationService] refuses it
     * (AC15) before [compute] or [describe] is ever called with it.
     */
    data class Fixed(val perAsset: Map<String, BigDecimal>) : FeeRule {
        init {
            require(perAsset.isNotEmpty()) { "a fixed fee needs a fee for at least one asset" }
            perAsset.forEach { (code, fee) ->
                require(fee.signum() >= 0) { "the fixed fee for $code must not be negative, was ${fee.toPlainString()}" }
            }
        }

        /** Whether [asset] has a fee here: an asset without one can't be priced by this rule. */
        fun hasFeeFor(asset: Asset): Boolean = asset.code in perAsset

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = feeFor(asset)

        override fun nominalRate(): BigDecimal = BigDecimal.ZERO

        /** Exact: startup validation has refused a fee with more decimals than its asset's scale. */
        override fun describe(asset: Asset): String = "Flat fee of ${feeFor(asset).setScale(asset.scale).toPlainString()} ${asset.code}"

        private fun feeFor(asset: Asset): BigDecimal =
            requireNotNull(perAsset[asset.code]) { "no fixed fee is configured for ${asset.code}" }
    }

    /** No fee at all, in any asset; [description] says why ("No fee for charity donations"). */
    data class NoFee(val description: String) : FeeRule {
        init {
            require(description.isNotBlank()) { "a no-fee rule needs a description" }
        }

        override fun compute(amount: BigDecimal, asset: Asset): BigDecimal = BigDecimal.ZERO

        override fun nominalRate(): BigDecimal = BigDecimal.ZERO

        override fun describe(asset: Asset): String = description
    }
}
