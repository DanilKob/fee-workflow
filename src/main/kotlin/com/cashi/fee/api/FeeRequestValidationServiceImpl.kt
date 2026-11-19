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
 * The JSON shape here, every domain check in [TransactionFactory], the price in [FeeCalculationService].
 *
 * Checked here: each field's JSON type, a number for `amount` and a string for the rest. No number
 * or boolean is coerced into a label or an id. A field of the wrong JSON type reaches the factory as
 * missing, and its one violation there (`is required`) is listed with the JSON-type message instead,
 * in the same place; a null or absent field is the factory's to report. Everything else (presence,
 * the id pattern, the amount's bounds, the labels and the rules between them, the state and the
 * strict `created_at`) is the factory's, in its words.
 *
 * Pricing, and with it fee vs amount (AC13), runs once the amount, the asset, the asset type and the
 * type have all passed the factory, whatever it said about the other fields: a fee larger than the
 * amount is listed with an invalid state or `created_at` (AC27), and no field is listed twice.
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
     * Fee vs amount (AC13), once the four fields the price reads have passed the factory: from the
     * transaction when there is one, otherwise from the input, whose amount is then known to be
     * valid money in its asset. Empty when the price can't be read yet or is within the amount.
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

    /**
     * A field that must be a JSON string: its text, or null when it is missing, `null`, or of
     * another JSON type, which is recorded in [wrongType].
     */
    private fun string(node: JsonNode?, field: TransactionField, wrongType: MutableMap<TransactionField, String>): String? =
        when {
            node == null || node.isNull -> null
            !node.isString -> null.also { wrongType[field] = "must be a JSON string" }
            else -> node.stringValue()
        }

    /**
     * A field that must be a JSON number: its exact decimal value (Jackson's number length bounds
     * its digits), or null when it is missing, `null`, or of another JSON type, recorded in [wrongType].
     */
    private fun number(node: JsonNode?, field: TransactionField, wrongType: MutableMap<TransactionField, String>): BigDecimal? =
        when {
            node == null || node.isNull -> null
            !node.isNumber -> null.also { wrongType[field] = "must be a JSON number" }
            else -> node.decimalValue()
        }

    private companion object {
        /** The fields the price reads: a violation on any of them means there is nothing to price yet. */
        val PRICED_FIELDS: Set<TransactionField> =
            setOf(TransactionField.AMOUNT, TransactionField.ASSET, TransactionField.ASSET_TYPE, TransactionField.TYPE)

        /** The field's snake_case name on the wire. */
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
