package com.cashi.fee.domain

/**
 * One registered transaction type. [label] is the canonical spelling, matched exactly (Q11);
 * [ruleId] and [version] are stored on every record, so an audit can tell which rule priced it.
 */
data class TransactionType(
    val label: String,
    val rule: FeeRule,
    val allowedAssetTypes: Set<AssetType>,
    val ruleId: String,
    val version: Int,
) {
    init {
        require(label.isNotBlank()) { "a transaction type needs a label" }
        require(allowedAssetTypes.isNotEmpty()) { "$label allows no asset type" }
        require(ruleId.isNotBlank()) { "$label needs a rule id" }
        require(version >= 1) { "$label needs a rule version of at least 1" }
    }
}

/**
 * The single list of transaction types (D5). Validation and calculation both look types up here;
 * an unknown label is a rejection, never a default fee.
 */
interface FeeSchedule {

    val types: List<TransactionType>

    fun find(label: String): TransactionType? = types.firstOrNull { it.label == label }

    fun labels(): List<String> = types.map { it.label }
}
