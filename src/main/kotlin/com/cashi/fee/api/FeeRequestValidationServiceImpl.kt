package com.cashi.fee.api

import com.cashi.fee.domain.AssetRegistry
import com.cashi.fee.domain.FeeCalculation
import com.cashi.fee.domain.FeeCalculationService
import com.cashi.fee.domain.Money
import com.cashi.fee.domain.RuleViolation
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionCreation
import com.cashi.fee.domain.TransactionFactory
import com.cashi.fee.domain.TransactionField
import com.cashi.fee.domain.TransactionInput
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Checks only each field's JSON type here: a number for `amount`, a string for the rest. Every
 * domain check is left to [TransactionFactory], and the price to [FeeCalculationService].
 *
 * A field of the wrong JSON type reaches the factory as missing. Its `is required` is then replaced
 * by the JSON-type message, in the same place in the list.
 *
 * Pricing runs once the four priced fields pass, whatever the other fields say. So a fee larger
 * than the amount is listed next to, say, an invalid state, and no field is listed twice.
 */
@Service
class FeeRequestValidationServiceImpl(
    private val transactions: TransactionFactory,
    private val calculation: FeeCalculationService,
    private val assets: AssetRegistry,
) : FeeRequestValidationService {

    override fun validate(request: TransactionFeeRequest): Transaction {
        val wrongType = mutableMapOf<TransactionField, String>()
        val input = TransactionInput(
            transactionId = string(request.transactionId, TransactionField.TRANSACTION_ID, wrongType),
            amount = number(request.amount, TransactionField.AMOUNT, wrongType),
            asset = string(request.asset, TransactionField.ASSET, wrongType),
            assetType = string(request.assetType, TransactionField.ASSET_TYPE, wrongType),
            type = string(request.type, TransactionField.TYPE, wrongType),
            state = string(request.state, TransactionField.STATE, wrongType),
            createdAt = string(request.createdAt, TransactionField.CREATED_AT, wrongType),
        )

        val creation = transactions.create(input)
        val violations = when (creation) {
            is TransactionCreation.Created -> emptyList()
            is TransactionCreation.Invalid -> creation.violations
        } + pricingViolations(input, creation)

        if (creation is TransactionCreation.Created && violations.isEmpty()) return creation.transaction
        throw RequestValidationException(violations.map { InvalidField(it.field.jsonName, wrongType[it.field] ?: it.message) })
    }

    override fun validateId(transactionId: String): String {
        val violation = Transaction.checkId(transactionId) ?: return transactionId
        throw RequestValidationException(listOf(InvalidField(violation.field.jsonName, violation.message)))
    }

    /**
     * Fee vs amount, once the four priced fields have passed the factory. Without a transaction it
     * reads the input, whose amount is then known to be valid money in its asset.
     */
    private fun pricingViolations(input: TransactionInput, creation: TransactionCreation): List<RuleViolation> {
        val (amount, type) = when (creation) {
            is TransactionCreation.Created -> creation.transaction.amount to creation.transaction.type
            is TransactionCreation.Invalid -> {
                if (creation.violations.any { it.field in PRICED_FIELDS }) return emptyList()
                val asset = input.asset?.let(assets::find) ?: return emptyList()
                val amount = input.amount ?: return emptyList()
                Money(amount, asset) to (input.type ?: return emptyList())
            }
        }
        return when (val calculation = calculation.quote(amount, type)) {
            is FeeCalculation.Quoted -> emptyList()
            is FeeCalculation.Rejected -> calculation.violations
        }
    }

    /** The field's text, or null. A non-string value is also recorded in [wrongType]. */
    private fun string(node: JsonNode?, field: TransactionField, wrongType: MutableMap<TransactionField, String>): String? =
        when {
            node == null || node.isNull -> null
            !node.isString -> null.also { wrongType[field] = "must be a JSON string" }
            else -> node.stringValue()
        }

    /**
     * The field's exact decimal value, or null. A non-number is also recorded in [wrongType].
     * Jackson's number length limit bounds the digits.
     */
    private fun number(node: JsonNode?, field: TransactionField, wrongType: MutableMap<TransactionField, String>): BigDecimal? =
        when {
            node == null || node.isNull -> null
            !node.isNumber -> null.also { wrongType[field] = "must be a JSON number" }
            else -> node.decimalValue()
        }

    private companion object {
        /** The fields the price reads. A violation on any of them means nothing can be priced. */
        val PRICED_FIELDS: Set<TransactionField> =
            setOf(TransactionField.AMOUNT, TransactionField.ASSET, TransactionField.ASSET_TYPE, TransactionField.TYPE)

        val TransactionField.jsonName: String
            get() = when (this) {
                TransactionField.TRANSACTION_ID -> "transaction_id"
                TransactionField.AMOUNT -> "amount"
                TransactionField.ASSET -> "asset"
                TransactionField.ASSET_TYPE -> "asset_type"
                TransactionField.TYPE -> "type"
                TransactionField.STATE -> "state"
                TransactionField.CREATED_AT -> "created_at"
            }
    }
}
