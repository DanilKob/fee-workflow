package com.cashi.fee.workflow

import com.cashi.fee.domain.Transaction
import com.cashi.fee.service.FeeWorkflowGateway
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.RunStatus
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.GatewayPhase
import com.cashi.fee.service.RejectionReason
import com.cashi.fee.workflow.model.FeeWorkflowOutput
import com.cashi.fee.workflow.model.RejectionMetadata
import com.cashi.fee.workflow.model.WorkflowStatus
import com.cashi.fee.workflow.model.toResult
import com.cashi.fee.workflow.model.toWorkflowInput
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.client.SendResponse.SendStatus
import dev.restate.client.kotlin.getOutputSuspend
import dev.restate.client.kotlin.response
import dev.restate.client.kotlin.sendStatus
import dev.restate.client.kotlin.toWorkflow
import dev.restate.client.kotlin.workflowHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The only class that touches the Restate [Client], [IngressException] or [SendStatus].
 *
 * The run is sent, not called, so the service can wait with its own deadline. The output is
 * polled, never attached, so nothing is held open on the server.
 */
@Component
class RestateFeeWorkflowGateway(
    private val client: Client,
    private val jsonMapper: JsonMapper,
) : FeeWorkflowGateway {

    override suspend fun send(transaction: Transaction, fingerprint: String): SendResult {
        val input = transaction.toWorkflowInput(fingerprint)
        return try {
            val response = client.toWorkflow<FeeWorkflow>(input.transactionId).request { run(input) }.send()
            when (response.sendStatus) {
                SendStatus.ACCEPTED -> SendResult.Accepted
                SendStatus.PREVIOUSLY_ACCEPTED -> SendResult.Duplicate
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IngressException) {
            if (isAlreadyInvoked(failure)) SendResult.Duplicate
            else SendResult.Failed(failureOf(failure, input.transactionId, GatewayPhase.SEND))
        }
    }

    /**
     * Polls first after 50 ms, then doubles the interval up to 1 s. The client has no request
     * timeout, so a hung poll is cut off at the deadline plus [LAST_POLL_GRACE]. Only the polling
     * stops; the run keeps going.
     */
    override suspend fun awaitOutput(transactionId: String, deadline: Duration): OutputResult =
        withTimeoutOrNull(deadline + LAST_POLL_GRACE) { pollOutput(transactionId, deadline) } ?: OutputResult.Pending

    private suspend fun pollOutput(transactionId: String, deadline: Duration): OutputResult {
        val handle = client.workflowHandle<FeeWorkflowOutput>(WORKFLOW_NAME, transactionId)
        val started = TimeSource.Monotonic.markNow()
        var interval = FIRST_POLL
        while (true) {
            val remaining = deadline - started.elapsedNow()
            if (!remaining.isPositive()) return OutputResult.Pending
            delay(minOf(interval, remaining))
            val output =
                try {
                    handle.getOutputSuspend().response
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IngressException) {
                    return outputFailure(failure, transactionId)
                }
            if (output.isReady) return OutputResult.Ready(output.value.toResult(), output.value.fingerprint)
            interval = (interval * 2).coerceAtMost(MAX_POLL)
        }
    }

    override suspend fun status(transactionId: String): StatusResult =
        try {
            StatusResult.Known(client.toWorkflow<FeeWorkflow>(transactionId).request { status() }.call().response.toRunStatus())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IngressException) {
            StatusResult.Failed(failureOf(failure, transactionId, GatewayPhase.STATUS))
        }

    /** A tagged rejection if the error body carries a tag, otherwise a failure. */
    private fun outputFailure(failure: IngressException, transactionId: String): OutputResult {
        if (failure.statusCode >= 400) {
            val body = failure.responseBody?.let(::parseBody)
            val reason = RejectionMetadata.reasonOf(body?.metadata, body?.message)
            if (reason != null) return OutputResult.Rejected(failure.statusCode, reason, RejectionMetadata.detailOf(body?.message))
        }
        return OutputResult.Failed(failureOf(failure, transactionId, GatewayPhase.AWAIT_OUTPUT))
    }

    /**
     * Logs the failure once with its cause, because the returned value drops the cause and the
     * response never shows it. A duplicate send or a tagged rejection is an outcome, not a failure.
     */
    private fun failureOf(failure: IngressException, transactionId: String, phase: GatewayPhase): GatewayFailure {
        log.warn(
            "restate ingress call failed: transaction_id={} phase={} status={}",
            transactionId,
            phase,
            failure.statusCode,
            failure,
        )
        return classify(failure)
    }

    /** Restate's error body: `{"message": …}`, with the failure's metadata when it carries any. */
    private fun parseBody(bytes: ByteArray): ErrorBody? =
        try {
            val node = jsonMapper.readTree(bytes)
            val metadata = node.get("metadata")?.takeIf(JsonNode::isObject)
                ?.properties()?.associate { (key, value) -> key to value.asString() }
            ErrorBody(node.get("message")?.asString(), metadata)
        } catch (_: Exception) {
            null
        }

    /**
     * The workflow only stores known tags. An unknown tag can't occur, and would read as no
     * rejection.
     */
    private fun WorkflowStatus.toRunStatus(): RunStatus =
        RunStatus(
            fingerprint = fingerprint,
            rejection = rejection?.let { stored ->
                RejectionReason.fromWire(stored.reason)?.let { RunRejection(stored.status, it, stored.detail) }
            },
        )

    private data class ErrorBody(val message: String?, val metadata: Map<String, String>?)

    internal companion object {
        private val log: Logger = LoggerFactory.getLogger(RestateFeeWorkflowGateway::class.java)

        /**
         * Only a refused connection or an unresolved host may promise "nothing was started". Any
         * other transport failure may have reached Restate, so its outcome is unknown. A failure
         * with a status is that status, whatever its cause.
         */
        internal fun classify(failure: IngressException): GatewayFailure =
            when {
                failure.statusCode >= 0 -> GatewayFailure.Status(failure.statusCode)
                failure.causes().any { it is ConnectException || it is UnresolvedAddressException || it is UnknownHostException } ->
                    GatewayFailure.Unreachable
                else -> GatewayFailure.OutcomeUnknown
            }

        /**
         * Restate's 409 "the workflow method was already invoked": a duplicate, not a failure. A
         * `send` normally reports this as `PREVIOUSLY_ACCEPTED`. Any other 409, such as a cancel or
         * kill, is not a duplicate.
         */
        internal fun isAlreadyInvoked(failure: IngressException): Boolean =
            failure.statusCode == 409 &&
                failure.responseBody?.let { String(it, Charsets.UTF_8) }?.contains("already", ignoreCase = true) == true

        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

        private const val WORKFLOW_NAME = "FeeWorkflow"
        private val FIRST_POLL: Duration = 50.milliseconds
        private val MAX_POLL: Duration = 1.seconds

        /**
         * How long the last poll may run past the deadline. `SyncWaitCheck` keeps the request
         * timeout above the deadline plus this.
         */
        internal val LAST_POLL_GRACE: Duration = 500.milliseconds
    }
}
