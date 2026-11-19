package com.cashi.fee.domain

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Builds transactions over an [AssetRegistry] and the fee rules of a [FeeCalculationService]. Plain
 * Kotlin: `config/DomainConfig` makes it a bean.
 */
class TransactionFactoryImpl(
    private val assets: AssetRegistry,
    private val calculation: FeeCalculationService,
) : TransactionFactory {

    override fun create(input: TransactionInput): TransactionCreation {
        val violations = mutableListOf<RuleViolation>()

        val transactionId: String? = present(input.transactionId, TransactionField.TRANSACTION_ID, violations)?.let { id ->
            Transaction.checkId(id)?.let { violations += it; null } ?: id
        }

        // The amount's scale depends on its asset, so the asset is looked up first. A present asset
        // code goes to the fee rules as it is: an unknown one is theirs to report.
        val assetCode: String? = input.asset?.takeUnless { it.isBlank() }
        val asset: Asset? = assetCode?.let(assets::find)

        val amount: Money? = amount(input.amount, asset, violations)

        // Listed after the amount, in the request's field order.
        if (assetCode == null) violations += RuleViolation(TransactionField.ASSET, RuleViolation.REQUIRED)

        val assetType: AssetType? = present(input.assetType, TransactionField.ASSET_TYPE, violations, blankIsMissing = false)?.let { value ->
            AssetType.entries.firstOrNull { it.name == value }
                ?: null.also { violations += RuleViolation(TransactionField.ASSET_TYPE, "must be one of ${AssetType.entries.joinToString(", ")}") }
        }

        // Likewise the type label: an unknown one is the fee rules' to report, with the valid labels.
        val type: String? = present(input.type, TransactionField.TYPE, violations)

        val state: String? = present(input.state, TransactionField.STATE, violations, blankIsMissing = false)?.let { value ->
            value.takeIf { it == Transaction.ELIGIBLE_STATE }
                ?: null.also { violations += RuleViolation(TransactionField.STATE, "must be ${Transaction.ELIGIBLE_STATE}") }
        }

        // Strict (D8, AC6): exactly `yyyy-MM-dd HH:mm:ss.SSSSSS`, a four-digit unsigned year (0001-9999),
        // resolved strictly, no zone.
        val createdAt: LocalDateTime? = present(input.createdAt, TransactionField.CREATED_AT, violations, blankIsMissing = false)?.let { value ->
            DateFormatter.parse(value)
                ?: null.also { violations += RuleViolation(TransactionField.CREATED_AT, "must be a date-time in the format yyyy-MM-dd HH:mm:ss.SSSSSS") }
        }

        // The rules that need no amount always run: a field already reported is passed as null, so
        // its rules are skipped and the others still run. An invalid amount hides none of them, and
        // a missing type or asset type hides no rule on the other fields (AC27).
        violations += calculation.checkRules(assetCode, assetType, type)

        if (violations.isNotEmpty()) return TransactionCreation.Invalid(violations)

        // With no violation, every field is present and valid, and the asset and type labels are
        // registered ones, in their registry spelling (Q11).
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

    /** The value, or null after recording it as missing on [field]: null, or blank when [blankIsMissing]. */
    private fun present(value: String?, field: TransactionField, violations: MutableList<RuleViolation>, blankIsMissing: Boolean = true): String? =
        when {
            value == null || (blankIsMissing && value.isBlank()) -> null.also { violations += RuleViolation(field, RuleViolation.REQUIRED) }
            else -> value
        }

    /**
     * The amount as money in [asset], or null after recording why not; null without a violation
     * when the asset is missing or unknown, which is the asset's to report. The bounds for every
     * asset come first, the integer digits and then the decimals, then the sign; the asset's own
     * scale comes last. Each reads precision, sign and scale only.
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
