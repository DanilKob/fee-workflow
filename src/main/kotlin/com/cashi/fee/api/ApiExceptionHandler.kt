package com.cashi.fee.api

import com.cashi.fee.service.RejectionReason
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.WebRequest
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/**
 * The one place errors become RFC 9457 `application/problem+json`. Spring MVC's other exceptions
 * (wrong method, wrong media type, …) keep the base class's handling.
 *
 * A problem carries only fixed details and field errors, never an exception's message. Each 5xx is
 * logged once, with the `transaction_id` and the phase when known.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(RequestValidationException::class)
    fun invalidRequest(failure: RequestValidationException): ResponseEntity<ProblemDetail> =
        invalidRequestProblem("One or more fields are invalid.", failure.errors).toResponse()

    /**
     * Malformed JSON, a body that isn't a JSON object, or no body. The same 400 problem as invalid
     * fields, with no field listed, since the body never bound to any.
     */
    public override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        handleExceptionInternal(ex, invalidRequestProblem("The request body is not a valid JSON object.", emptyList()), headers, HttpStatus.BAD_REQUEST, request)

    /** The status is the reason's own; only the problem's type, title and detail are chosen here. */
    @ExceptionHandler(WorkflowRejectedException::class)
    fun workflowRejected(failure: WorkflowRejectedException): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.valueOf(failure.reason.httpStatus)
        return when (failure.reason) {
            RejectionReason.PAYLOAD_CONFLICT ->
                problem(status, "payload-conflict", "Payload conflict", RejectionReason.PAYLOAD_CONFLICT_DETAIL)
            RejectionReason.RULE_REJECTED ->
                problem(status, "rule-rejected", "Rejected by the fee rules", failure.detail)
        }.toResponse()
    }

    @ExceptionHandler(FeeNotFoundException::class)
    fun feeNotFound(failure: FeeNotFoundException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "fee-not-found", "Fee not found", "No fee is known for this transaction_id.").toResponse()

    /** A 503 with the unavailability's fixed wording and its `Retry-After`. */
    @ExceptionHandler(FeeUnavailableException::class)
    fun unavailable(failure: FeeUnavailableException): ResponseEntity<ProblemDetail> {
        log.warn(
            "fee request unavailable: transaction_id={} phase={} reason={}",
            failure.transactionId,
            failure.phase,
            failure.unavailability,
        )
        val unavailability = failure.unavailability
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, unavailability.retryAfterSeconds.toString())
            .body(unavailableProblem(unavailability.detail))
    }

    @ExceptionHandler(GatewayFailureException::class)
    fun gatewayFailure(failure: GatewayFailureException): ResponseEntity<ProblemDetail> {
        log.error("fee request failed: transaction_id={} phase={} failure={}", failure.transactionId, failure.phase, failure.failure)
        return internalError()
    }

    /**
     * The request timeout is set above `fees.sync-wait`, so only a hung call gets here. The run may
     * or may not have started, and the 503 says so.
     */
    public override fun handleAsyncRequestTimeoutException(
        ex: AsyncRequestTimeoutException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        log.error("fee request timed out: transaction_id={} phase={}", transactionIdOf(request), REQUEST_TIMEOUT_PHASE)
        val retryAfter = HttpHeaders().apply {
            addAll(headers)
            set(HttpHeaders.RETRY_AFTER, TIMEOUT_RETRY_AFTER_SECONDS)
        }
        return handleExceptionInternal(ex, unavailableProblem(TIMEOUT_DETAIL), retryAfter, HttpStatus.SERVICE_UNAVAILABLE, request)
    }

    /** Logged with the `transaction_id` if the controller got that far. The problem stays fixed. */
    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.error("fee request failed unexpectedly: transaction_id={}", transactionIdOf(request), failure)
        return internalError()
    }

    private fun transactionIdOf(request: WebRequest): Any =
        request.getAttribute(TRANSACTION_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) ?: "unknown"

    private fun invalidRequestProblem(detail: String, errors: List<InvalidField>): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail).apply { setProperty("errors", errors) }

    private fun unavailableProblem(detail: String): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Service unavailable", detail)

    private fun internalError(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", "The fee request could not be completed.")
            .toResponse()

    private fun problem(status: HttpStatus, type: String, title: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create("/problems/$type")
            this.title = title
        }

    private fun ProblemDetail.toResponse(): ResponseEntity<ProblemDetail> = ResponseEntity.status(status).body(this)

    private companion object {
        const val REQUEST_TIMEOUT_PHASE = "REQUEST_TIMEOUT"
        const val TIMEOUT_RETRY_AFTER_SECONDS = "5"
        const val TIMEOUT_DETAIL = "the fee request timed out; outcome unknown; resending the same request is safe"
    }
}
