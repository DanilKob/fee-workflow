package com.cashi.fee.domain

/** The request field a [RuleViolation] is about. In lower case, its name is the JSON field name. */
enum class TransactionField { TRANSACTION_ID, AMOUNT, ASSET, ASSET_TYPE, TYPE, STATE, CREATED_AT }

/** One reason the domain refuses a transaction. [message] is a fixed text, safe to show. */
data class RuleViolation(val field: TransactionField, val message: String) {

    companion object {
        /** The message for a field that is missing, or blank where blank means nothing. */
        const val REQUIRED: String = "is required"
    }
}
