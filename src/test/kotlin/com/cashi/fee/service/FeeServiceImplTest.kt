package com.cashi.fee.service

import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.FeeResult
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.PayloadFingerprintServiceImpl
import com.cashi.fee.domain.Transaction
import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.FeeWorkflowGateway.OutputResult
import com.cashi.fee.service.FeeWorkflowGateway.RunRejection
import com.cashi.fee.service.FeeWorkflowGateway.RunStatus
import com.cashi.fee.service.FeeWorkflowGateway.SendResult
import com.cashi.fee.service.FeeWorkflowGateway.StatusResult
import com.cashi.fee.service.GatewayFailureMappingService.Outcome
import com.cashi.fee.support.TestTransactions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * The fee request and lookup flows, against a scripted gateway and record store, with the real
 * fingerprint and failure-mapping services. The last tests swap each service for a stub, to show
 * the injected one is used.
 */
class FeeServiceImplTest {

    private val transaction = TestTransactions.transaction()
    private val fingerprint = PayloadFingerprintServiceImpl().of(transaction)
    private val otherFingerprint = "f".repeat(64)
    private val result = FeeResult("txn_001", BigDecimal("1000"), "USD", "Mobile Top Up", BigDecimal("1.50"), BigDecimal("0.0015"), "Standard fee rate of 0.15%")
    private val properties = FeeProperties(Duration.ofMillis(1500))

    private fun record(fingerprint: String) = FeeRecord(
        transactionId = "txn_001",
        amount = BigDecimal("1000"),
        asset = "USD",
        assetType = AssetType.FIAT,
        type = "Mobile Top Up",
        fee = BigDecimal("1.50"),
        rate = BigDecimal("0.0015"),
        description = "Standard fee rate of 0.15%",
        ruleId = "mobile-top-up",
        ruleVersion = 1,
        createdAt = transaction.createdAt,
        chargeId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        chargedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0, 1),
        fingerprint = fingerprint,
    )

    private fun service(
        gateway: FakeGateway,
        records: FakeRecords = FakeRecords(),
        fingerprints: PayloadFingerprintService = PayloadFingerprintServiceImpl(),
        failureMapping: GatewayFailureMappingService = GatewayFailureMappingServiceImpl(),
    ) = FeeServiceImpl(gateway, records, fingerprints, failureMapping, properties)

    private val conflict = FeeChargeResult.Rejected(RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL)

    // --- POST, a new run ---

    @Test
    fun `an accepted run's result with the same fingerprint completes, waiting up to fees sync-wait`() = runTest {
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Ready(result, fingerprint))
        val records = FakeRecords()

        assertEquals(FeeChargeResult.Completed(result), service(gateway, records).chargeFee(transaction))
        assertEquals(listOf(transaction to fingerprint), gateway.sent)
        assertEquals(listOf("txn_001" to 1.5.seconds), gateway.awaited)
        assertEquals(emptyList<String>(), records.lookedUp, "a new run needs no record lookup")
    }

    @Test
    fun `a run started with another payload is a payload conflict, never its result`() = runTest {
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Ready(result, otherFingerprint))

        assertEquals(conflict, service(gateway).chargeFee(transaction))
    }

    @Test
    fun `a tagged rejection from the run is passed on with its detail`() = runTest {
        val detail = "type: unknown type; valid types: Mobile Top Up, Bank Transfer, Charity Donation"
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, detail))

        assertEquals(FeeChargeResult.Rejected(RejectionReason.RULE_REJECTED, detail), service(gateway).chargeFee(transaction))
    }

    @Test
    fun `an accepted run still going at the deadline is processing (AC28)`() = runTest {
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Pending)

        assertEquals(FeeChargeResult.Processing, service(gateway).chargeFee(transaction))
        assertEquals(emptyList<String>(), gateway.statusRead, "this payload started the run; nothing to check")
    }

    @Test
    fun `a transport failure while waiting for an accepted run is processing (case 5)`() = runTest {
        listOf(GatewayFailure.Unreachable, GatewayFailure.OutcomeUnknown).forEach { failure ->
            val gateway = FakeGateway(SendResult.Accepted, OutputResult.Failed(failure))

            assertEquals(FeeChargeResult.Processing, service(gateway).chargeFee(transaction), failure.toString())
        }
    }

    @Test
    fun `a run that ended with an untagged failure fails in the await phase (a 500)`() = runTest {
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Failed(GatewayFailure.Status(500)))

        assertEquals(FeeChargeResult.Failed(GatewayPhase.AWAIT_OUTPUT, GatewayFailure.Status(500)), service(gateway).chargeFee(transaction))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sendFailures")
    fun `a failed send is unavailable with its own wording, and waits for nothing (cases 1 to 3)`(failure: GatewayFailure, expected: Unavailability) =
        runTest {
            val gateway = FakeGateway(SendResult.Failed(failure), OutputResult.Ready(result, fingerprint))

            assertEquals(FeeChargeResult.Unavailable(GatewayPhase.SEND, expected), service(gateway).chargeFee(transaction))
            assertEquals(emptyList<Any>(), gateway.awaited)
        }

    // --- POST, a duplicate send ---

    @Test
    fun `a duplicate whose record has the same fingerprint gets the record, with no wait`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Pending)
        val records = FakeRecords(record = record(fingerprint))

        assertEquals(FeeChargeResult.Completed(record(fingerprint).toResult()), service(gateway, records).chargeFee(transaction))
        assertEquals(listOf("txn_001"), records.lookedUp)
        assertEquals(emptyList<Any>(), gateway.awaited)
        assertEquals(emptyList<String>(), gateway.statusRead)
    }

    @Test
    fun `a duplicate whose record has another fingerprint is a payload conflict, with no wait (D4)`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, fingerprint))

        assertEquals(conflict, service(gateway, FakeRecords(record = record(otherFingerprint))).chargeFee(transaction))
        assertEquals(emptyList<Any>(), gateway.awaited)
    }

    @Test
    fun `a duplicate whose record lookup fails is a 503 that never falls through to the workflow (case 6)`() = runTest {
        listOf(DataAccessResourceFailureException("db down"), CannotCreateTransactionException("no connection")).forEach { failure ->
            val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, fingerprint), StatusResult.Known(RunStatus(fingerprint)))

            assertEquals(
                FeeChargeResult.Unavailable(GatewayPhase.RECORD_LOOKUP, Unavailability.RECORDS_UNAVAILABLE),
                service(gateway, FakeRecords(failure = failure)).chargeFee(transaction),
            )
            assertEquals(emptyList<Any>(), gateway.awaited)
            assertEquals(emptyList<String>(), gateway.statusRead)
        }
    }

    /** The status is read before anything waits. */
    @Test
    fun `a duplicate whose run stored another payload's fingerprint is a payload conflict at once, with no wait`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, fingerprint), StatusResult.Known(RunStatus(otherFingerprint)))

        assertEquals(conflict, service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001"), gateway.statusRead)
        assertEquals(emptyList<Any>(), gateway.awaited)
    }

    /** The same problem the first POST got, without waiting for a run that has already ended. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storedRejections")
    fun `a duplicate whose run stored this payload's rejection gets that rejection at once, with no wait`(stored: RunRejection) = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Pending, StatusResult.Known(RunStatus(fingerprint, stored)))

        assertEquals(FeeChargeResult.Rejected(stored.reason, stored.detail), service(gateway).chargeFee(transaction))
        assertEquals(emptyList<Any>(), gateway.awaited)
    }

    @Test
    fun `a duplicate whose run status can't be read before waiting is a 503, with no wait (case 4)`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, fingerprint), StatusResult.Failed(GatewayFailure.OutcomeUnknown))

        assertEquals(FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.STATUS_UNAVAILABLE), service(gateway).chargeFee(transaction))
        assertEquals(emptyList<Any>(), gateway.awaited)
    }

    // A duplicate whose run stored this payload's fingerprint: the run is this payload's, so wait.

    @Test
    fun `a duplicate of this payload's run waits for it and gets its result (AC16)`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, fingerprint), StatusResult.Known(RunStatus(fingerprint)))

        assertEquals(FeeChargeResult.Completed(result), service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001" to 1.5.seconds), gateway.awaited)
        assertEquals(listOf("txn_001"), gateway.statusRead)
    }

    @Test
    fun `a duplicate of this payload's run still going at the deadline is processing, with no status re-read (AC28)`() = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Pending, StatusResult.Known(RunStatus(fingerprint)))

        assertEquals(FeeChargeResult.Processing, service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001"), gateway.statusRead, "the payload was checked before the wait")
    }

    @Test
    fun `a transport failure while waiting for this payload's run is processing, with no status re-read (case 5)`() = runTest {
        listOf(GatewayFailure.Unreachable, GatewayFailure.OutcomeUnknown).forEach { failure ->
            val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Failed(failure), StatusResult.Known(RunStatus(fingerprint)))

            assertEquals(FeeChargeResult.Processing, service(gateway).chargeFee(transaction), failure.toString())
            assertEquals(listOf("txn_001"), gateway.statusRead)
        }
    }

    /** The fee rules refused this payload after its run started. */
    @Test
    fun `a duplicate of this payload's run that ends rule_rejected is that rejection (a 400)`() = runTest {
        val detail = "amount: the fee is larger than the amount"
        val gateway = FakeGateway(
            SendResult.Duplicate,
            OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, detail),
            StatusResult.Known(RunStatus(fingerprint)),
        )

        assertEquals(FeeChargeResult.Rejected(RejectionReason.RULE_REJECTED, detail), service(gateway).chargeFee(transaction))
        assertEquals(400, RejectionReason.RULE_REJECTED.httpStatus)
    }

    /**
     * Restate's cancel and kill also end with 409, so an untagged failure is never a payload
     * conflict.
     */
    @Test
    fun `a duplicate's run that ended with an untagged failure is a 500, never a 409`() = runTest {
        listOf(RunStatus(fingerprint), RunStatus()).forEach { stored ->
            val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Failed(GatewayFailure.Status(409)), StatusResult.Known(stored))

            assertEquals(
                FeeChargeResult.Failed(GatewayPhase.AWAIT_OUTPUT, GatewayFailure.Status(409)),
                service(gateway).chargeFee(transaction),
                "status before the wait: $stored",
            )
        }
    }

    // A duplicate whose run has stored no fingerprint yet: wait, then check the payload.

    @Test
    fun `a duplicate of a run with no fingerprint yet waits, and the output's fingerprint decides`() = runTest {
        listOf(fingerprint to FeeChargeResult.Completed(result), otherFingerprint to conflict).forEach { (ran, expected) ->
            val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Ready(result, ran), StatusResult.Known(RunStatus()))

            assertEquals(expected, service(gateway).chargeFee(transaction), "the run had $ran")
            assertEquals(listOf("txn_001" to 1.5.seconds), gateway.awaited)
            assertEquals(listOf("txn_001"), gateway.statusRead, "a result carries its fingerprint; no re-read")
        }
    }

    @Test
    fun `a duplicate of a run with no fingerprint yet that shows another payload's at the deadline is a payload conflict`() = runTest {
        val gateway = FakeGateway(
            SendResult.Duplicate,
            OutputResult.Pending,
            StatusResult.Known(RunStatus()),
            reread = StatusResult.Known(RunStatus(otherFingerprint)),
        )

        assertEquals(conflict, service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001", "txn_001"), gateway.statusRead)
        assertEquals(1, gateway.awaited.size)
    }

    /** A payload that hasn't been checked never gets a 202. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rechecks")
    fun `a duplicate of a run with no fingerprint yet, still going at the deadline, is decided by the status read again`(
        reread: StatusResult,
        expected: FeeChargeResult,
    ) = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, OutputResult.Pending, StatusResult.Known(RunStatus()), reread = reread)

        assertEquals(expected, service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001", "txn_001"), gateway.statusRead)
    }

    @Test
    fun `a transport failure while waiting for a run with no fingerprint yet reads the status again (case 5)`() = runTest {
        listOf(otherFingerprint to conflict, fingerprint to FeeChargeResult.Processing).forEach { (stored, expected) ->
            val gateway = FakeGateway(
                SendResult.Duplicate,
                OutputResult.Failed(GatewayFailure.OutcomeUnknown),
                StatusResult.Known(RunStatus()),
                reread = StatusResult.Known(RunStatus(stored)),
            )

            assertEquals(expected, service(gateway).chargeFee(transaction))
            assertEquals(listOf("txn_001", "txn_001"), gateway.statusRead)
        }
    }

    /** A rejection carries no fingerprint, so the status read again says whose it is. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectionRechecks")
    fun `a rejection of a run that had no fingerprint yet is passed on only once the run is known to have this payload`(
        reread: StatusResult,
        expected: FeeChargeResult,
    ) = runTest {
        val gateway = FakeGateway(SendResult.Duplicate, REJECTED, StatusResult.Known(RunStatus()), reread = reread)

        assertEquals(expected, service(gateway).chargeFee(transaction))
        assertEquals(listOf("txn_001", "txn_001"), gateway.statusRead)
    }

    // --- GET ---

    @Test
    fun `a lookup with a record answers the record's result, and reads no status`() = runTest {
        val gateway = FakeGateway()
        val records = FakeRecords(record = record(fingerprint))

        assertEquals(FeeChargeResult.Completed(record(fingerprint).toResult()), service(gateway, records).lookUpFee("txn_001"))
        assertEquals(listOf("txn_001"), records.lookedUp)
        assertEquals(emptyList<String>(), gateway.statusRead)
    }

    @Test
    fun `a lookup whose record lookup fails is a 503 that never reads the status (case 6)`() = runTest {
        val gateway = FakeGateway(status = StatusResult.Known(RunStatus(fingerprint)))

        assertEquals(
            FeeChargeResult.Unavailable(GatewayPhase.RECORD_LOOKUP, Unavailability.RECORDS_UNAVAILABLE),
            service(gateway, FakeRecords(failure = DataAccessResourceFailureException("db down"))).lookUpFee("txn_001"),
        )
        assertEquals(emptyList<String>(), gateway.statusRead)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookups")
    fun `a lookup with no record is decided by the run's status`(status: StatusResult, expected: FeeChargeResult) = runTest {
        val gateway = FakeGateway(status = status)

        assertEquals(expected, service(gateway).lookUpFee("txn_001"))
        assertEquals(emptyList<Any>(), gateway.sent)
        assertEquals(emptyList<Any>(), gateway.awaited)
    }

    // --- cancellation: a CancellationException is rethrown, never mapped ---

    @Test
    fun `a cancellation is rethrown, never mapped to an answer`() = runTest {
        val cancelled = CancellationException("the caller went away")

        assertThrows<CancellationException> { service(FakeGateway(), FakeRecords(failure = cancelled)).lookUpFee("txn_001") }
        assertThrows<CancellationException> {
            service(FakeGateway(SendResult.Duplicate), FakeRecords(failure = cancelled)).chargeFee(transaction)
        }
        assertThrows<CancellationException> { service(FakeGateway(SendResult.Accepted, cancelOutput = cancelled)).chargeFee(transaction) }
    }

    /**
     * Only an unavailable store is a 503. Anything else is a bug, and stays the catch-all's 500.
     */
    @Test
    fun `a record lookup failure that isn't a data access failure is not mapped`() = runTest {
        assertThrows<IllegalStateException> {
            service(FakeGateway(), FakeRecords(failure = IllegalStateException("bug"))).lookUpFee("txn_001")
        }
    }

    // --- the injected services ---

    @Test
    fun `the fingerprint sent and compared is the injected fingerprint service's`() = runTest {
        val stubbed = "s".repeat(64)
        val fingerprints = object : PayloadFingerprintService {
            override fun of(transaction: Transaction): String = stubbed
        }
        val gateway = FakeGateway(SendResult.Accepted, OutputResult.Ready(result, stubbed))

        assertEquals(FeeChargeResult.Completed(result), service(gateway, fingerprints = fingerprints).chargeFee(transaction))
        assertEquals(listOf(transaction to stubbed), gateway.sent)
    }

    @Test
    fun `a failed call is answered as the injected failure mapping says`() = runTest {
        val asked = mutableListOf<Triple<GatewayPhase, GatewayFailure, Boolean>>()
        val mapping = object : GatewayFailureMappingService {
            override fun outcomeOf(phase: GatewayPhase, failure: GatewayFailure, payloadChecked: Boolean): Outcome =
                Outcome.Processing.also { asked += Triple(phase, failure, payloadChecked) }

            override fun recordLookupFailed(): Outcome.Unavailable = Outcome.Unavailable(Unavailability.NOT_STARTED_YET)
        }

        // The real mapping makes a refused send a 503. This one says 202, and the service obeys.
        val refused = service(FakeGateway(SendResult.Failed(GatewayFailure.Unreachable)), failureMapping = mapping).chargeFee(transaction)
        val lookup = service(FakeGateway(), FakeRecords(failure = DataAccessResourceFailureException("down")), failureMapping = mapping).lookUpFee("txn_001")

        assertEquals(FeeChargeResult.Processing, refused)
        assertEquals(listOf(Triple(GatewayPhase.SEND, GatewayFailure.Unreachable, true)), asked)
        assertEquals(FeeChargeResult.Unavailable(GatewayPhase.RECORD_LOOKUP, Unavailability.NOT_STARTED_YET), lookup)
    }

    /**
     * Answers every call with the scripted result and records what it was asked. A call that has no
     * scripted answer fails the test.
     */
    private class FakeGateway(
        private val send: SendResult? = null,
        private val output: OutputResult? = null,
        private val status: StatusResult? = null,
        private val reread: StatusResult? = null,
        private val cancelOutput: CancellationException? = null,
    ) : FeeWorkflowGateway {
        val sent = mutableListOf<Pair<Transaction, String>>()
        val awaited = mutableListOf<Pair<String, kotlin.time.Duration>>()
        val statusRead = mutableListOf<String>()

        override suspend fun send(transaction: Transaction, fingerprint: String): SendResult {
            sent += transaction to fingerprint
            return send ?: error("a send, but none was scripted")
        }

        override suspend fun awaitOutput(transactionId: String, deadline: kotlin.time.Duration): OutputResult {
            awaited += transactionId to deadline
            cancelOutput?.let { throw it }
            return output ?: error("a wait, but no output was scripted")
        }

        /** The first read answers [status] and the second [reread]. A third read fails the test. */
        override suspend fun status(transactionId: String): StatusResult {
            statusRead += transactionId
            return when (statusRead.size) {
                1 -> status ?: error("the status was read, but none was scripted")
                2 -> reread ?: error("the status was read again, but no re-read was scripted")
                else -> error("the status was read ${statusRead.size} times")
            }
        }
    }

    /** A record store whose `find` answers [record] or throws [failure]. Nothing here saves. */
    private class FakeRecords(private val record: FeeRecord? = null, private val failure: Throwable? = null) : FeeRecordRepository {
        val lookedUp = mutableListOf<String>()

        override fun find(transactionId: String): FeeRecord? {
            lookedUp += transactionId
            failure?.let { throw it }
            return record
        }

        override fun save(record: FeeRecord) = error("the service never saves a record")
    }

    companion object {
        private val FP = PayloadFingerprintServiceImpl().of(TestTransactions.transaction())
        private val OTHER = "f".repeat(64)
        private val STORED_REJECTION = RunRejection(400, RejectionReason.RULE_REJECTED, "asset_type: USD is FIAT")

        @JvmStatic
        fun sendFailures() = listOf(
            arrayOf(GatewayFailure.Unreachable, Unavailability.NOTHING_STARTED),
            arrayOf(GatewayFailure.OutcomeUnknown, Unavailability.OUTCOME_UNKNOWN),
            arrayOf(GatewayFailure.Status(500), Unavailability.SEND_REFUSED),
        )

        private val CONFLICT = FeeChargeResult.Rejected(RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL)
        private val REJECTED = OutputResult.Rejected(400, RejectionReason.RULE_REJECTED, "asset_type: USD is FIAT")

        @JvmStatic
        fun storedRejections() = listOf(
            STORED_REJECTION,
            RunRejection(409, RejectionReason.PAYLOAD_CONFLICT, RejectionReason.PAYLOAD_CONFLICT_DETAIL),
        )

        @JvmStatic
        fun rechecks() = listOf(
            arrayOf(StatusResult.Known(RunStatus(OTHER)), CONFLICT),
            arrayOf(StatusResult.Known(RunStatus(FP)), FeeChargeResult.Processing),
            arrayOf(StatusResult.Known(RunStatus(FP, STORED_REJECTION)), FeeChargeResult.Rejected(STORED_REJECTION.reason, STORED_REJECTION.detail)),
            arrayOf(StatusResult.Known(RunStatus()), FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.NOT_STARTED_YET)),
            arrayOf(StatusResult.Failed(GatewayFailure.Unreachable), FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.STATUS_UNAVAILABLE)),
        )

        @JvmStatic
        fun rejectionRechecks() = listOf(
            arrayOf(StatusResult.Known(RunStatus(OTHER, STORED_REJECTION)), CONFLICT),
            arrayOf(StatusResult.Known(RunStatus(FP, STORED_REJECTION)), FeeChargeResult.Rejected(STORED_REJECTION.reason, STORED_REJECTION.detail)),
            arrayOf(StatusResult.Known(RunStatus(FP)), FeeChargeResult.Rejected(REJECTED.reason, REJECTED.detail)),
            arrayOf(StatusResult.Known(RunStatus()), FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.NOT_STARTED_YET)),
            arrayOf(StatusResult.Failed(GatewayFailure.OutcomeUnknown), FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.STATUS_UNAVAILABLE)),
        )

        @JvmStatic
        fun lookups() = listOf(
            arrayOf(StatusResult.Known(RunStatus(FP, STORED_REJECTION)), FeeChargeResult.Rejected(STORED_REJECTION.reason, STORED_REJECTION.detail)),
            arrayOf(StatusResult.Known(RunStatus(FP)), FeeChargeResult.Processing),
            arrayOf(StatusResult.Known(RunStatus()), FeeChargeResult.NotFound),
            arrayOf(StatusResult.Failed(GatewayFailure.OutcomeUnknown), FeeChargeResult.Unavailable(GatewayPhase.STATUS, Unavailability.STATUS_UNAVAILABLE)),
        )
    }
}
