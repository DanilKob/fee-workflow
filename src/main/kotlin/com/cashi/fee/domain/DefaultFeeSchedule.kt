package com.cashi.fee.domain

import java.math.BigDecimal

/**
 * The shipped transaction types. Adding a type means one entry here plus one row in
 * `transaction_types.feature`. `DomainConfig` validates the schedule at startup, so a malformed
 * one stops the application. `FeeScheduleContractTest` checks every entry.
 */
class DefaultFeeSchedule : FeeSchedule {

    override val types: List<TransactionType> = listOf(
        TransactionType(
            label = "Mobile Top Up",
            rule = FeeRule.Percentage(BigDecimal("0.0015")),
            allowedAssetTypes = setOf(AssetType.FIAT, AssetType.CRYPTO),
            ruleId = "mobile-top-up",
            version = 1,
        ),
        TransactionType(
            label = "Bank Transfer",
            rule = FeeRule.Fixed(
                mapOf(
                    "USD" to BigDecimal("0.50"),
                    "EUR" to BigDecimal("0.50"),
                    "GBP" to BigDecimal("0.50"),
                    "JPY" to BigDecimal("75"),
                ),
            ),
            allowedAssetTypes = setOf(AssetType.FIAT),
            ruleId = "bank-transfer",
            version = 1,
        ),
        TransactionType(
            label = "Charity Donation",
            rule = FeeRule.NoFee("No fee for charity donations"),
            allowedAssetTypes = setOf(AssetType.FIAT, AssetType.CRYPTO),
            ruleId = "charity-donation",
            version = 1,
        ),
    )
}
