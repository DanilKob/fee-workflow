package com.cashi.fee.domain

class FeeCalculationServiceImpl(private val assets: AssetRegistry, private val schedule: FeeSchedule) : FeeCalculationService {

    override fun checkRules(assetCode: String?, assetType: AssetType?, typeLabel: String?): List<RuleViolation> {
        val violations = mutableListOf<RuleViolation>()

        val type = typeLabel?.let { label ->
            schedule.find(label)
                ?: null.also { violations += RuleViolation(TransactionField.TYPE, "unknown type; valid types: ${schedule.labels().joinToString(", ")}") }
        }

        val asset = assetCode?.let { code ->
            assets.find(code) ?: null.also { violations += RuleViolation(TransactionField.ASSET, "unknown asset") }
        }

        if (asset != null && assetType != null && asset.type != assetType) {
            violations += RuleViolation(TransactionField.ASSET_TYPE, "${asset.code} is ${asset.type}")
        }

        if (type != null) {
            violations += typeRules(type, asset, asset?.type ?: assetType)
        }

        return violations
    }

    /**
     * Checks [assetType], then for a fixed fee a fee in [asset]. The second check is skipped once
     * the first fails, so a disallowed asset type is reported once.
     */
    private fun typeRules(type: TransactionType, asset: Asset?, assetType: AssetType?): List<RuleViolation> {
        if (assetType != null && assetType !in type.allowedAssetTypes) {
            val allowed = type.allowedAssetTypes.sorted().joinToString(", ")
            return listOf(RuleViolation(TransactionField.TYPE, "${type.label} does not allow $assetType; allowed asset types: $allowed"))
        }
        if (asset != null && !type.rule.prices(asset)) {
            return listOf(RuleViolation(TransactionField.ASSET, "${type.label} has no fee configured for ${asset.code}"))
        }
        return emptyList()
    }

    /** Only a fixed fee depends on the asset. */
    private fun FeeRule.prices(asset: Asset): Boolean =
        when (this) {
            is FeeRule.Fixed -> hasFeeFor(asset)
            is FeeRule.Percentage, is FeeRule.NoFee -> true
        }

    override fun quote(transaction: Transaction): FeeCalculation = quote(transaction.amount, transaction.type)

    override fun quote(amount: Money, typeLabel: String): FeeCalculation {
        val asset = amount.asset
        val type = schedule.find(typeLabel)
        val violations = checkRules(asset.code, asset.type, typeLabel)

        if (type == null || violations.isNotEmpty()) {
            return FeeCalculation.Rejected(violations)
        }

        val fee = Money.rounded(type.rule.compute(amount.amount, asset), asset)
        if (fee > amount) {
            return FeeCalculation.Rejected(
                listOf(RuleViolation(TransactionField.AMOUNT, "must be at least the fee of ${fee.amount.toPlainString()} ${asset.code}")),
            )
        }
        return FeeCalculation.Quoted(
            FeeQuote(
                type = type.label,
                fee = fee,
                rate = type.rule.nominalRate(),
                description = type.rule.describe(asset),
                ruleId = type.ruleId,
                ruleVersion = type.version,
            ),
        )
    }
}
