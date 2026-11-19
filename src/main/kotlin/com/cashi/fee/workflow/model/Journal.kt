@file:UseSerializers(BigDecimalAsString::class, LocalDateTimeAsString::class)

package com.cashi.fee.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.LocalDateTime

// The types that cross the Restate journal and its state. Money is a JSON string, times are ISO
// strings. Their JSON shape is a compatibility contract: a paused invocation replays the journal
// written by an older deployment, and FeeCharge keeps its state indefinitely. Add fields only
// with defaults; never rename or remove one.
//
// Every type pins its serial name with @SerialName, and every sealed subtype its discriminator,
// so renaming a Kotlin class never changes what is written. Never change a serial name.

/** The validated request, as the workflow's input. The fingerprint is computed by the service. */
@Serializable
@SerialName("FeeWorkflowInput")
data class FeeWorkflowInput(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val assetType: String,
    val type: String,
    val state: String,
    val createdAt: LocalDateTime,
    val fingerprint: String,
)

/** The `lookup` step's result: the record already stored for this id, if any. */
@Serializable
@SerialName("LookupJournal")
data class LookupJournal(val stored: RecordJournal? = null)

/** A priced fee, as the `calculate` step journals it. */
@Serializable
@SerialName("QuoteJournal")
data class QuoteJournal(
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
    val ruleId: String,
    val ruleVersion: Int,
)

/** The `calculate` step's result: a quote, or the fee rules' refusal. */
@Serializable
@SerialName("CalculationJournal")
sealed interface CalculationJournal {
    @Serializable
    @SerialName("quoted")
    data class Quoted(val quote: QuoteJournal) : CalculationJournal

    @Serializable
    @SerialName("rejected")
    data class Rejected(val detail: String) : CalculationJournal
}

/** What the workflow asks FeeCharge to charge. */
@Serializable
@SerialName("ChargeRequest")
data class ChargeRequest(val fee: BigDecimal, val asset: String, val fingerprint: String)

/** A charge, as FeeCharge keeps it in its state forever (AC25). */
@Serializable
@SerialName("Charge")
data class Charge(
    val chargeId: String,
    val fee: BigDecimal,
    val asset: String,
    val fingerprint: String,
    val chargedAt: LocalDateTime,
)

/** The journaled form of a `fee_record` row (a domain `FeeRecord`). */
@Serializable
@SerialName("RecordJournal")
data class RecordJournal(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val assetType: String,
    val type: String,
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
    val ruleId: String,
    val ruleVersion: Int,
    val createdAt: LocalDateTime,
    val chargeId: String,
    val chargedAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val fingerprint: String,
)

/**
 * A pre-charge rejection, kept in workflow state so later reads answer the same problem (Q8).
 * [reason] is the wire tag of a `RejectionReason`.
 */
@Serializable
@SerialName("Rejection")
data class Rejection(val status: Int, val reason: String, val detail: String)

/** What `status()` answers: the payload fingerprint and the rejection, when stored. */
@Serializable
@SerialName("WorkflowStatus")
data class WorkflowStatus(val fingerprint: String? = null, val rejection: Rejection? = null)

/** The workflow's output: the 200 body's fields, plus the fingerprint the service compares and strips. */
@Serializable
@SerialName("FeeWorkflowOutput")
data class FeeWorkflowOutput(
    val transactionId: String,
    val amount: BigDecimal,
    val asset: String,
    val type: String,
    val fee: BigDecimal,
    val rate: BigDecimal,
    val description: String,
    val fingerprint: String,
)
