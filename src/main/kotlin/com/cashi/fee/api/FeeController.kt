package com.cashi.fee.api

import com.cashi.fee.service.FeeChargeResult
import com.cashi.fee.service.FeeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as RequestBodyDoc
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Validates the input, asks [FeeService], and maps the result to HTTP. Only a 200 or a 202 is
 * returned directly. Every other answer is an exception that [ApiExceptionHandler] renders.
 *
 * The endpoints are suspend functions run through Reactor's `mono {}`, so nothing here may block a
 * thread or call `runBlocking`.
 */
@Tag(name = "Transaction fees")
@RestController
class FeeController(
    private val validator: FeeRequestValidationService,
    private val feeService: FeeService,
) {

    @Operation(
        summary = "Charge the fee of a settled transaction",
        description = "Validates the transaction, then charges its fee once and records it, through the fee workflow. " +
            "It waits up to fees.sync-wait (10 s by default) for the result; a run still going then answers 202 " +
            "with a Location to poll. Resending the same payload is safe and answers the same body; the same " +
            "transaction_id with a different payload is a 409.",
        requestBody = RequestBodyDoc(
            required = true,
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(name = "brief", summary = "The brief's sample transaction", description = FeeApiExamples.REQUEST_DESCRIPTION, value = FeeApiExamples.REQUEST)],
                ),
            ],
        ),
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The fee was charged and recorded; a resend of the same payload gets the same body", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TransactionFeeResponse::class), examples = [ExampleObject(name = "brief", value = FeeApiExamples.RESPONSE)])]),
        ApiResponse(responseCode = "202", description = "Still running after fees.sync-wait: GET the Location after Retry-After seconds", headers = [Header(name = HttpHeaders.LOCATION, description = "/transaction/{transaction_id}/fee"), Header(name = HttpHeaders.RETRY_AFTER, description = "seconds")], content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FeeProcessingResponse::class), examples = [ExampleObject(name = "processing", value = FeeApiExamples.PROCESSING)])]),
        ApiResponse(responseCode = "400", description = "Invalid input, every invalid field listed in errors; or a fee rule rejected the transaction after the workflow started (rule_rejected)", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = [ExampleObject(name = "invalid", value = FeeApiExamples.INVALID)])]),
        ApiResponse(responseCode = "409", description = "This transaction_id was already used with a different payload; nothing was charged or changed", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = [ExampleObject(name = "payload conflict", value = FeeApiExamples.PAYLOAD_CONFLICT)])]),
        ApiResponse(responseCode = "503", description = "Restate or the fee records are unavailable; the detail says whether anything started. Resending the same request is safe after Retry-After", headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "seconds")], content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = [ExampleObject(name = "unavailable", value = FeeApiExamples.UNAVAILABLE)])]),
        ApiResponse(responseCode = "500", description = "The run ended with an untagged failure; logged", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)]),
    )
    @PostMapping("/transaction/fee")
    suspend fun chargeFee(@RequestBody request: TransactionFeeRequest, @Parameter(hidden = true) servletRequest: HttpServletRequest): ResponseEntity<Any> {
        val transaction = validator.validate(request)
        val id = transaction.transactionId
        servletRequest.rememberTransactionId(id)
        return respond(id, feeService.chargeFee(transaction))
    }

    /** The id is checked first, so a bad id is a 400 that queries nothing. */
    @Operation(
        summary = "Get the fee charged for a transaction",
        description = "The POST's 200 body once the fee is recorded, 202 while the workflow is still running, " +
            "the POST's problem after the workflow rejected the transaction, and 404 for an unknown transaction_id.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The fee is recorded: exactly the POST's 200 body", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TransactionFeeResponse::class), examples = [ExampleObject(name = "brief", value = FeeApiExamples.RESPONSE)])]),
        ApiResponse(responseCode = "202", description = "The workflow is still running: ask again after Retry-After seconds", headers = [Header(name = HttpHeaders.LOCATION, description = "/transaction/{transaction_id}/fee"), Header(name = HttpHeaders.RETRY_AFTER, description = "seconds")], content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FeeProcessingResponse::class), examples = [ExampleObject(name = "processing", value = FeeApiExamples.PROCESSING)])]),
        ApiResponse(responseCode = "400", description = "The transaction_id is malformed, or the workflow rejected the transaction with a 400 (rule_rejected)", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)]),
        ApiResponse(responseCode = "404", description = "No fee is known for this transaction_id", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = [ExampleObject(name = "not found", value = FeeApiExamples.NOT_FOUND)])]),
        ApiResponse(responseCode = "409", description = "The workflow rejected the transaction: its transaction_id was already charged for another payload", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = [ExampleObject(name = "payload conflict", value = FeeApiExamples.PAYLOAD_CONFLICT)])]),
        ApiResponse(responseCode = "503", description = "Restate or the fee records are unavailable; asking again is safe after Retry-After", headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "seconds")], content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)]),
        ApiResponse(responseCode = "500", description = "The run ended with an untagged failure; logged", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)]),
    )
    @GetMapping("/transaction/{transactionId}/fee")
    suspend fun fee(
        @Parameter(description = "The transaction_id the fee was requested for", example = "txn_001") @PathVariable transactionId: String,
        @Parameter(hidden = true) servletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val id = validator.validateId(transactionId)
        servletRequest.rememberTransactionId(id)
        return respond(id, feeService.lookUpFee(id))
    }

    /** POST and GET answer alike: the same 200 body, the same 202, the same problems. */
    private fun respond(id: String, result: FeeChargeResult): ResponseEntity<Any> =
        when (result) {
            is FeeChargeResult.Completed -> ResponseEntity.ok(TransactionFeeResponse.from(result.result))
            FeeChargeResult.Processing ->
                ResponseEntity.accepted()
                    .location(statusLink(id))
                    .header(HttpHeaders.RETRY_AFTER, PROCESSING_RETRY_AFTER_SECONDS)
                    .body(FeeProcessingResponse(id))
            is FeeChargeResult.Rejected -> throw WorkflowRejectedException(id, result.reason, result.detail)
            is FeeChargeResult.Unavailable -> throw FeeUnavailableException(id, result.phase, result.unavailability)
            is FeeChargeResult.Failed -> throw GatewayFailureException(id, result.phase, result.failure)
            FeeChargeResult.NotFound -> throw FeeNotFoundException(id)
        }

    /**
     * For the handler's log lines. A request attribute, not MDC, because the handler runs after
     * this coroutine has ended, on whichever thread Spring's async dispatch picks.
     */
    private fun HttpServletRequest.rememberTransactionId(id: String) = setAttribute(TRANSACTION_ID_ATTRIBUTE, id)

    private companion object {
        const val PROCESSING_RETRY_AFTER_SECONDS = "1"

        /** The id has passed [com.cashi.fee.domain.Transaction.ID_PATTERN], so it needs no encoding. */
        fun statusLink(id: String): URI = URI.create("/transaction/$id/fee")
    }
}
