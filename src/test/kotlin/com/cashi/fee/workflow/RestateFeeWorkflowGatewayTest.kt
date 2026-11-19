package com.cashi.fee.workflow

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.serde.kotlinx.KotlinSerializationSerdeFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * How the gateway reads a failed ingress call (plan §3 step 4, cases 1–3; Q4, AC27). "Nothing was
 * started" is a promise only a refused connection or an unresolved host may make; every other
 * transport failure may have reached Restate, and is an unknown outcome.
 *
 * And how it bounds a wait whose poll hangs: the Restate client sets no request timeout, so only
 * the gateway's own cutoff (the deadline plus `LAST_POLL_GRACE`) keeps a stalled output read from
 * holding the request until `spring.mvc.async.request-timeout` turns it into a 503 instead of the
 * AC28 202.
 */
class RestateFeeWorkflowGatewayTest {

    /** Without the cutoff the wait would never end: the timeout fails the test instead of hanging the build. */
    @Test
    @Timeout(10)
    fun `a poll that never gets an answer is cut off at the deadline plus the grace, and the wait is pending`() {
        StalledIngress(answerFirstPollNotReady = false).use { ingress ->
            val (result, elapsed) = awaitOutputThrough(ingress, DEADLINE)

            assertEquals(OutputResult.Pending, result)
            assertEquals(1, ingress.requests.get(), "the wait sent one poll, which never got an answer")
            assertCutOffInTime(elapsed)
        }
    }

    @Test
    @Timeout(10)
    fun `a poll that stalls after an earlier not-ready answer is cut off in time too`() {
        StalledIngress(answerFirstPollNotReady = true).use { ingress ->
            val (result, elapsed) = awaitOutputThrough(ingress, DEADLINE)

            assertEquals(OutputResult.Pending, result)
            assertEquals(2, ingress.requests.get(), "the first poll was answered 470 (not ready), the second never")
            assertCutOffInTime(elapsed)
        }
    }

    private fun awaitOutputThrough(ingress: StalledIngress, deadline: Duration): Pair<OutputResult, Duration> {
        val client = Client.connect(ingress.baseUri, KotlinSerializationSerdeFactory())
        val gateway = RestateFeeWorkflowGateway(client, JsonMapper.builder().build())
        val started = TimeSource.Monotonic.markNow()
        val result = runBlocking { gateway.awaitOutput("txn_stalled_poll", deadline) }
        return result to started.elapsedNow()
    }

    /** Never before the deadline; never much past the deadline plus the last poll's grace. */
    private fun assertCutOffInTime(elapsed: Duration) {
        assertTrue(elapsed >= DEADLINE) { "gave up after $elapsed, before the $DEADLINE deadline" }
        val limit = DEADLINE + RestateFeeWorkflowGateway.LAST_POLL_GRACE + MARGIN
        assertTrue(elapsed < limit) { "the stalled poll held the wait for $elapsed, past $limit" }
    }

    /**
     * A local "Restate ingress" that accepts connections and reads requests but never answers them,
     * except, when asked, the first poll, with Restate's 470 "not ready".
     */
    private class StalledIngress(private val answerFirstPollNotReady: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val connections = CopyOnWriteArrayList<Socket>()
        val requests = AtomicInteger()
        val baseUri: String = "http://127.0.0.1:${server.localPort}"

        init {
            Thread.ofVirtual().start {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections += socket
                    Thread.ofVirtual().start { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            runCatching {
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    // One request: its head ends with an empty line; a GET has no body.
                    generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.count().takeIf { it > 0 } ?: return
                    if (requests.incrementAndGet() == 1 && answerFirstPollNotReady) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 470 Not Ready\r\ncontent-length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            connections.forEach { runCatching { it.close() } }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedOrUnresolved")
    fun `a refused connection or an unresolved host is unreachable, nothing was started`(cause: Throwable) {
        assertEquals(GatewayFailure.Unreachable, RestateFeeWorkflowGateway.classify(transportFailure(cause)))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mayHaveReachedRestate")
    fun `any other transport failure is an unknown outcome, never unreachable`(cause: Throwable) {
        assertEquals(GatewayFailure.OutcomeUnknown, RestateFeeWorkflowGateway.classify(transportFailure(cause)))
    }

    @Test
    fun `a transport failure with no cause is an unknown outcome`() {
        assertEquals(GatewayFailure.OutcomeUnknown, RestateFeeWorkflowGateway.classify(transportFailure(null)))
    }

    @Test
    fun `a failure with a status is that status, whatever its cause`() {
        assertEquals(GatewayFailure.Status(500), RestateFeeWorkflowGateway.classify(failure(500)))
        assertEquals(GatewayFailure.Status(503), RestateFeeWorkflowGateway.classify(failure(503, cause = ConnectException("Connection refused"))))
        assertEquals(GatewayFailure.Status(409), RestateFeeWorkflowGateway.classify(failure(409, body = """{"message":"canceled"}""")))
    }

    @Test
    fun `a 409 saying the workflow was already invoked is a duplicate send`() {
        assertTrue(RestateFeeWorkflowGateway.isAlreadyInvoked(failure(409, body = """{"message":"the workflow method was already invoked"}""")))
    }

    /** Restate's cancel and kill also end with 409: only the "already invoked" wording makes a duplicate. */
    @Test
    fun `any other 409, or another status, is not a duplicate`() {
        assertFalse(RestateFeeWorkflowGateway.isAlreadyInvoked(failure(409, body = """{"message":"the invocation was killed"}""")))
        assertFalse(RestateFeeWorkflowGateway.isAlreadyInvoked(failure(409)))
        assertFalse(RestateFeeWorkflowGateway.isAlreadyInvoked(failure(500, body = """{"message":"already invoked"}""")))
        assertFalse(RestateFeeWorkflowGateway.isAlreadyInvoked(transportFailure(IOException("already closed"))))
    }

    /** A failure with no response at all: the client reports status −1. */
    private fun transportFailure(cause: Throwable?) = failure(-1, cause = cause)

    private fun failure(status: Int, cause: Throwable? = null, body: String? = null) =
        IngressException("ingress call failed", "POST", "http://restate:8080/FeeWorkflow/txn_001/run/send", status, body?.toByteArray(), cause)

    companion object {
        private val DEADLINE: Duration = 200.milliseconds

        /** Scheduling slack on a busy build machine. */
        private val MARGIN: Duration = 500.milliseconds

        @JvmStatic
        fun refusedOrUnresolved(): List<Throwable> = listOf(
            ConnectException("Connection refused"),
            UnknownHostException("restate"),
            UnresolvedAddressException(),
            IOException("wrapped", ConnectException("Connection refused")),
            ConnectException("unresolved").apply { initCause(UnresolvedAddressException()) },
        )

        @JvmStatic
        fun mayHaveReachedRestate(): List<Throwable> = listOf(
            IOException("Connection reset"),
            HttpTimeoutException("request timed out"),
            IOException("HTTP/1.1 header parser received no bytes"),
        )
    }
}
