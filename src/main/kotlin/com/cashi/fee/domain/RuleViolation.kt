package com.cashi.fee.domain

/**
 * The input field a [RuleViolation] is about: every field of a fee request. The API maps each one to
 * its JSON field name; the workflow writes it in lower case, which is the same snake_case name.
 */
enum class TransactionField { TRANSACTION_ID, AMOUNT, ASSET, ASSET_TYPE, TYPE, STATE, CREATED_AT }

/** One reason the domain refuses a transaction. [message] is a fixed text, safe to show. */
data class RuleViolation(val field: TransactionField, val message: String) {

    companion object {
        /** The message for a field that is missing, or blank where blank means nothing. */
        const val REQUIRED: String = "is required"
    }
}
