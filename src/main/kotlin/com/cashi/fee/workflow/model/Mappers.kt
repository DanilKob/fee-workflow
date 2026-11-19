package com.cashi.fee.workflow.model

import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.DateFormatter
import com.cashi.fee.domain.FeeQuote
import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeResult
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionInput
import java.time.LocalDateTime
import java.util.UUID

// Mappers between the journal types and the domain. Every runBlock result is a journal type, so
// the domain stays free of serialization. Money is a value object in the domain but a BigDecimal
// plus an asset code here, so the journal's JSON shape doesn't change.

/** The amount crosses as the BigDecimal it was sent as, scale included. */
fun Transaction.toWorkflowInput(fingerprint: String): FeeWorkflowInput =
    FeeWorkflowInput(transactionId, amount.amount, asset, assetType.name, type, state, createdAt, fingerprint)

/**
 * The input's fields, unchecked, for [com.cashi.fee.domain.TransactionFactory]. A direct ingress
 * call skips the API's checks, so a bad input must end in a rejection, never in a step that throws
 * and retries forever. A year outside 0001–9999 goes in as an empty string, which the factory
 * refuses.
 */
fun FeeWorkflowInput.toTransactionInput(): TransactionInput =
    TransactionInput(transactionId, amount, asset, assetType, type, state, DateFormatter.formatOrNull(createdAt).orEmpty())

/** The fee as its BigDecimal, at the asset's scale, as `calculate` has always journaled it. */
fun FeeQuote.toJournal(): QuoteJournal = QuoteJournal(fee.amount, rate, description, ruleId, ruleVersion)

fun FeeRecord.toJournal(): RecordJournal =
    RecordJournal(
        transactionId = transactionId,
        amount = amount,
        asset = asset,
        assetType = assetType.name,
        type = type,
        fee = fee,
        rate = rate,
        description = description,
        ruleId = ruleId,
        ruleVersion = ruleVersion,
        createdAt = createdAt,
        chargeId = chargeId.toString(),
        chargedAt = chargedAt,
        recordedAt = recordedAt,
        fingerprint = fingerprint,
    )

fun RecordJournal.toDomain(): FeeRecord =
    FeeRecord(
        transactionId = transactionId,
        amount = amount,
        asset = asset,
        assetType = AssetType.valueOf(assetType),
        type = type,
        fee = fee,
        rate = rate,
        description = description,
        ruleId = ruleId,
        ruleVersion = ruleVersion,
        createdAt = createdAt,
        chargeId = UUID.fromString(chargeId),
        chargedAt = chargedAt,
        recordedAt = recordedAt,
        fingerprint = fingerprint,
    )

/**
 * Fee and asset come from the charge, not the quote, so the record always matches what was
 * charged, even if a rule change has moved the fee since.
 */
fun recordJournal(input: FeeWorkflowInput, quote: QuoteJournal, charge: Charge, recordedAt: LocalDateTime): RecordJournal =
    RecordJournal(
        transactionId = input.transactionId,
        amount = input.amount,
        asset = charge.asset,
        assetType = input.assetType,
        type = input.type,
        fee = charge.fee,
        rate = quote.rate,
        description = quote.description,
        ruleId = quote.ruleId,
        ruleVersion = quote.ruleVersion,
        createdAt = input.createdAt,
        chargeId = charge.chargeId,
        chargedAt = charge.chargedAt,
        recordedAt = recordedAt,
        fingerprint = input.fingerprint,
    )

fun RecordJournal.toOutput(): FeeWorkflowOutput =
    FeeWorkflowOutput(transactionId, amount, asset, type, fee, rate, description, fingerprint)

fun FeeWorkflowOutput.toResult(): FeeResult = FeeResult(transactionId, amount, asset, type, fee, rate, description)
