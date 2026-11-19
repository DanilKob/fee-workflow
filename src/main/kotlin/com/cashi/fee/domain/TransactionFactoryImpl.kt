package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime

class TransactionFactoryImpl(
    private val assets: AssetRegistry,
    private val calculation: FeeCalculationService,
) : TransactionFactory {

    override fun create(input: TransactionInput): TransactionCreation {
        val violations = mutableListOf<RuleViolation>()

        val transactionId: String? = present(input.transactionId, TransactionField.TRANSACTION_ID, violations)?.let { id ->
            Transaction.checkId(id)?.let { violations += it; null } ?: id
        }

        // The amount's scale depends on its asset, so the asset is looked up first. An unknown code
        // is left for the fee rules to report.
        val assetCode: String? = input.asset?.takeUnless { it.isBlank() }
        val asset: Asset? = assetCode?.let(assets::find)

        val amount: Money? = amount(input.amount, asset, violations)

        // Listed after the amount, in the request's field order.
        if (assetCode == null) violations += RuleViolation(TransactionField.ASSET, RuleViolation.REQUIRED)

        val assetType: AssetType? = present(input.assetType, TransactionField.ASSET_TYPE, violations, blankIsMissing = false)?.let { value ->
            AssetType.entries.firstOrNull { it.name == value }
                ?: null.also { violations += RuleViolation(TransactionField.ASSET_TYPE, "must be one of ${AssetType.entries.joinToString(", ")}") }
        }

        // Likewise, an unknown type is left for the fee rules, which list the valid labels.
        val type: String? = present(input.type, TransactionField.TYPE, violations)

        val state: String? = present(input.state, TransactionField.STATE, violations, blankIsMissing = false)?.let { value ->
            value.takeIf { it == Transaction.ELIGIBLE_STATE }
                ?: null.also { violations += RuleViolation(TransactionField.STATE, "must be ${Transaction.ELIGIBLE_STATE}") }
        }

        val createdAt: LocalDateTime? = present(input.createdAt, TransactionField.CREATED_AT, violations, blankIsMissing = false)?.let { value ->
            DateFormatter.parse(value)
                ?: null.also { violations += RuleViolation(TransactionField.CREATED_AT, "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS") }
        }

        // These rules always run. A field already reported is passed as null, so only the rules
        // that need it are skipped, and one invalid field hides no other.
        violations += calculation.checkRules(assetCode, assetType, type)

        if (violations.isNotEmpty()) return TransactionCreation.Invalid(violations)

        // No violation means every field is valid, and the labels are in their registry spelling.
        return TransactionCreation.Created(
            Transaction(
                transactionId = checkNotNull(transactionId),
                amount = checkNotNull(amount),
                assetType = checkNotNull(assetType),
                type = checkNotNull(type),
                state = checkNotNull(state),
                createdAt = checkNotNull(createdAt),
            ),
        )
    }

    /** [value], or null after recording [field] as missing (blank too, if [blankIsMissing]). */
    private fun present(value: String?, field: TransactionField, violations: MutableList<RuleViolation>, blankIsMissing: Boolean = true): String? =
        when {
            value == null || (blankIsMissing && value.isBlank()) -> null.also { violations += RuleViolation(field, RuleViolation.REQUIRED) }
            else -> value
        }

    /**
     * The amount as money in [asset], or null after recording why not. A valid amount with no known
     * asset gives null and records nothing: that is the asset's error. The asset-free bounds come
     * first, then the sign, then the asset's scale, each read from precision, sign and scale only.
     */
    private fun amount(value: BigDecimal?, asset: Asset?, violations: MutableList<RuleViolation>): Money? {
        if (value == null) return null.also { violations += RuleViolation(TransactionField.AMOUNT, RuleViolation.REQUIRED) }
        val violation = Money.checkBounds(value)
            ?: RuleViolation(TransactionField.AMOUNT, "must be greater than zero").takeIf { value.signum() <= 0 }
            ?: asset?.let { Money.check(value, it) }
        if (violation != null) return null.also { violations += violation }
        return asset?.let { Money(value, it) }
    }
}
