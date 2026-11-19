package com.cashi.fee.service

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.GatewayFailureMappingService.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Plan §3 step 4's failure cases, one test each, with the exact wording the caller gets (AC27, Q4). */
class GatewayFailureMappingServiceImplTest {

    private val mapping = GatewayFailureMappingServiceImpl()

    private val transport = listOf(GatewayFailure.Unreachable, GatewayFailure.OutcomeUnknown)
    private val every = transport + GatewayFailure.Status(500)

    @Test
    fun `case 1 - a refused or unresolved send says nothing was started, with Retry-After`() {
        val outcome = mapping.outcomeOf(GatewayPhase.SEND, GatewayFailure.Unreachable, payloadChecked = false)

        assertEquals(Outcome.Unavailable(Unavailability.NOTHING_STARTED), outcome)
        assertEquals("Restate unreachable; nothing was started; resend the same request", Unavailability.NOTHING_STARTED.detail)
        assertEquals(5, Unavailability.NOTHING_STARTED.retryAfterSeconds)
    }

    @Test
    fun `case 2 - any other transport failure of the send says the outcome is unknown (Q4)`() {
        val outcome = mapping.outcomeOf(GatewayPhase.SEND, GatewayFailure.OutcomeUnknown, payloadChecked = false)

        assertEquals(Outcome.Unavailable(Unavailability.OUTCOME_UNKNOWN), outcome)
        assertEquals("outcome unknown; resending the same request is safe", Unavailability.OUTCOME_UNKNOWN.detail)
    }

    @Test
    fun `case 3 - a send answered with an error status is a retryable 503`() {
        listOf(400, 500, 502, 503).forEach { code ->
            assertEquals(
                Outcome.Unavailable(Unavailability.SEND_REFUSED),
                mapping.outcomeOf(GatewayPhase.SEND, GatewayFailure.Status(code), payloadChecked = false),
            )
        }
        assertEquals("Restate refused the request; resending the same request is safe", Unavailability.SEND_REFUSED.detail)
    }

    @Test
    fun `case 4 - a failed status read is a 503, whatever the failure`() {
        every.forEach { failure ->
            listOf(true, false).forEach { checked ->
                assertEquals(Outcome.Unavailable(Unavailability.STATUS_UNAVAILABLE), mapping.outcomeOf(GatewayPhase.STATUS, failure, checked))
            }
        }
        assertEquals("fee workflow status unavailable; resending the same request is safe", Unavailability.STATUS_UNAVAILABLE.detail)
    }

    @Test
    fun `case 5 - a transport failure while waiting is a 202 once the payload is checked, else a status re-read`() {
        transport.forEach { failure ->
            assertEquals(Outcome.Processing, mapping.outcomeOf(GatewayPhase.AWAIT_OUTPUT, failure, payloadChecked = true))
            assertEquals(Outcome.RereadStatus, mapping.outcomeOf(GatewayPhase.AWAIT_OUTPUT, failure, payloadChecked = false))
        }
    }

    /** Plan §3 step 6: an untagged terminal failure (a cancel or a kill is a 409 too) is a 500, never a 409 or a 202. */
    @Test
    fun `a run that ended with an error status while waiting is a 500, checked or not`() {
        listOf(409, 500).forEach { code ->
            listOf(true, false).forEach { checked ->
                assertEquals(Outcome.RunFailed, mapping.outcomeOf(GatewayPhase.AWAIT_OUTPUT, GatewayFailure.Status(code), checked))
            }
        }
    }

    @Test
    fun `case 6 - a failed record lookup says the fee records are unavailable, with Retry-After`() {
        assertEquals(Outcome.Unavailable(Unavailability.RECORDS_UNAVAILABLE), mapping.recordLookupFailed())
        every.forEach { failure ->
            assertEquals(mapping.recordLookupFailed(), mapping.outcomeOf(GatewayPhase.RECORD_LOOKUP, failure, payloadChecked = true))
        }
        assertEquals("fee records unavailable; resending the same request is safe", Unavailability.RECORDS_UNAVAILABLE.detail)
        assertEquals(5, Unavailability.RECORDS_UNAVAILABLE.retryAfterSeconds)
    }

    /** Q4: "nothing was started" is the one promise only a refused connection may make. */
    @Test
    fun `only a refused connection says nothing was started`() {
        Unavailability.entries.filter { it != Unavailability.NOTHING_STARTED }.forEach {
            assertFalse(it.detail.contains("nothing was started")) { "$it: ${it.detail}" }
        }
    }
}
