package com.cashi.fee.api

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.GatewayPhase
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.service.Unavailability

/**
 * The request attribute that holds the validated `transaction_id`, so a 5xx the handler didn't
 * expect, or a request that timed out, is still logged with it (O1). Set by [FeeController], read
 * by [ApiExceptionHandler].
 */
const val TRANSACTION_ID_ATTRIBUTE: String = "com.cashi.fee.api.transaction_id"

/** The request breaks one or more rules; [errors] lists every one of them. */
class RequestValidationException(val errors: List<InvalidField>) :
    RuntimeException("invalid request fields: ${errors.map { it.field }}")

/** The workflow refused the transaction with a tagged rejection (or a fingerprint mismatch). */
class WorkflowRejectedException(val transactionId: String, val reason: RejectionReason, val detail: String) :
    RuntimeException("workflow $transactionId rejected: ${reason.wire}")

/** A call to the workflow failed; [failure] says how. */
class GatewayFailureException(val transactionId: String, val phase: GatewayPhase, val failure: GatewayFailure) :
    RuntimeException("workflow $transactionId failed in $phase: $failure")

/** Restate or the fee records can't answer now (a 503); [unavailability] carries the fixed wording. */
class FeeUnavailableException(val transactionId: String, val phase: GatewayPhase, val unavailability: Unavailability) :
    RuntimeException("fee request $transactionId unavailable in $phase: $unavailability")

/** Nothing is known for this `transaction_id`: no record, no rejection, no run (a 404). */
class FeeNotFoundException(val transactionId: String) : RuntimeException("no fee known for $transactionId")
