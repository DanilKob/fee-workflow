package com.cashi.fee.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.GatewayPhase
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.service.Unavailability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import java.net.URI

/** Each failure becomes the problem the API promises, with the status the rejection reason carries. */
class ApiExceptionHandlerTest {

    private val handler = ApiExceptionHandler()

    private fun webRequest(transactionId: String? = null) =
        ServletWebRequest(
            MockHttpServletRequest().apply { transactionId?.let { setAttribute(TRANSACTION_ID_ATTRIBUTE, it) } },
            MockHttpServletResponse(),
        )

    /** What [ApiExceptionHandler] logs while [block] runs. */
    private fun logsOf(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    @Test
    fun `invalid fields are a 400 that lists every one of them`() {
        val errors = listOf(InvalidField("amount", "must be a JSON number"), InvalidField("asset_type", "USD is FIAT"))

        val response = handler.invalidRequest(RequestValidationException(errors))

        assertEquals(400, response.statusCode.value())
        val problem = response.body!!
        assertEquals(URI.create("/problems/invalid-request"), problem.type)
        assertEquals(400, problem.status)
        assertEquals(errors, problem.properties?.get("errors"))
    }

    @Test
    fun `a body that isn't JSON is the same 400 problem, with no field listed and no parser message`() {
        val failure = HttpMessageNotReadableException("JSON parse error: Unexpected character ('x' (code 120))", MockHttpInputMessage(ByteArray(0)))

        val response = handler.handleHttpMessageNotReadable(failure, HttpHeaders(), HttpStatus.BAD_REQUEST, webRequest())!!

        assertEquals(400, response.statusCode.value())
        val problem = response.body as ProblemDetail
        assertEquals(URI.create("/problems/invalid-request"), problem.type)
        assertEquals("Invalid request", problem.title)
        assertEquals("The request body is not a valid JSON object.", problem.detail)
        assertEquals(emptyList<InvalidField>(), problem.properties?.get("errors"))
        assertFalse(problem.toString().contains("Unexpected character")) { problem.toString() }
    }

    @Test
    fun `a payload conflict is a 409 with the fixed detail`() {
        val response = handler.workflowRejected(WorkflowRejectedException("txn_001", RejectionReason.PAYLOAD_CONFLICT, "anything"))

        assertEquals(409, response.statusCode.value())
        val problem = response.body!!
        assertEquals(URI.create("/problems/payload-conflict"), problem.type)
        assertEquals(RejectionReason.PAYLOAD_CONFLICT_DETAIL, problem.detail)
    }

    @Test
    fun `a rule rejection is a 400 with the rules' detail`() {
        val response = handler.workflowRejected(WorkflowRejectedException("txn_001", RejectionReason.RULE_REJECTED, "type: unknown type"))

        assertEquals(400, response.statusCode.value())
        assertEquals(URI.create("/problems/rule-rejected"), response.body!!.type)
        assertEquals("type: unknown type", response.body!!.detail)
    }

    @Test
    fun `an unknown fee is a 404 problem`() {
        val response = handler.feeNotFound(FeeNotFoundException("txn_001"))

        assertEquals(404, response.statusCode.value())
        assertEquals(URI.create("/problems/fee-not-found"), response.body!!.type)
        assertEquals("No fee is known for this transaction_id.", response.body!!.detail)
    }

    @Test
    fun `an unavailability is a 503 with its fixed wording and Retry-After, logged once with the id and phase`() {
        Unavailability.entries.forEach { unavailability ->
            lateinit var response: org.springframework.http.ResponseEntity<ProblemDetail>
            val logs = logsOf { response = handler.unavailable(FeeUnavailableException("txn_001", GatewayPhase.SEND, unavailability)) }

            assertEquals(503, response.statusCode.value())
            assertEquals(URI.create("/problems/unavailable"), response.body!!.type)
            assertEquals(unavailability.detail, response.body!!.detail)
            assertEquals(unavailability.retryAfterSeconds.toString(), response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            assertEquals(1, logs.size, logs.toString())
            assertTrue(logs.single().formattedMessage.contains("transaction_id=txn_001 phase=SEND")) { logs.single().formattedMessage }
        }
    }

    @Test
    fun `a gateway failure is a 500 that never echoes the cause, logged once with the id and phase`() {
        lateinit var response: org.springframework.http.ResponseEntity<ProblemDetail>
        val logs = logsOf {
            response = handler.gatewayFailure(GatewayFailureException("txn_001", GatewayPhase.AWAIT_OUTPUT, GatewayFailure.Status(409)))
        }

        assertEquals(500, response.statusCode.value())
        assertEquals("The fee request could not be completed.", response.body!!.detail)
        assertEquals(1, logs.size, logs.toString())
        assertTrue(logs.single().formattedMessage.contains("transaction_id=txn_001 phase=AWAIT_OUTPUT")) { logs.single().formattedMessage }
    }

    /** Slice 1 advisory: the async timeout is logged once with the id and phase, in the /problems shape. */
    @Test
    fun `a request that timed out is a 503 problem with Retry-After, logged once with the id and phase`() {
        var response: org.springframework.http.ResponseEntity<Any>? = null
        val logs = logsOf {
            response = handler.handleAsyncRequestTimeoutException(
                AsyncRequestTimeoutException(),
                HttpHeaders(),
                HttpStatus.SERVICE_UNAVAILABLE,
                webRequest("txn_001"),
            )
        }

        val timedOut = response!!
        assertEquals(503, timedOut.statusCode.value())
        val problem = timedOut.body as ProblemDetail
        assertEquals(URI.create("/problems/unavailable"), problem.type)
        assertEquals("the fee request timed out; outcome unknown; resending the same request is safe", problem.detail)
        assertEquals("5", timedOut.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals(1, logs.size, logs.toString())
        assertTrue(logs.single().formattedMessage.contains("transaction_id=txn_001 phase=REQUEST_TIMEOUT")) { logs.single().formattedMessage }
    }

    @Test
    fun `an unexpected failure is a 500 that never echoes its message, with or without a transaction_id`() {
        listOf(null, "txn_001").forEach { id ->
            val response = handler.unexpected(IllegalStateException("secret internals"), webRequest(id))

            assertEquals(500, response.statusCode.value())
            assertEquals("The fee request could not be completed.", response.body!!.detail)
        }
    }
}
