package com.cashi.fee.workflow.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * The journal and state shapes are a compatibility contract. FeeCharge keeps its state
 * indefinitely, so a charge written by today's deployment must still decode in every later one.
 * Restate's kotlinx serde uses [Json.Default], as here.
 */
class JournalShapeTest {

    @Test
    fun `a checked-in Charge sample still decodes, and encodes back to the same JSON`() {
        val sample = javaClass.getResource("/journal/charge-v1.json")!!.readText().trim()

        val charge = Json.decodeFromString<Charge>(sample)

        assertEquals(
            Charge(
                chargeId = "3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f",
                fee = BigDecimal("1.50"),
                asset = "USD",
                fingerprint = "a0de743b9a60a3bc322ba13619efba691d84e68444823b69f41cf27ea8b09798",
                chargedAt = LocalDateTime.of(2026, 11, 19, 10, 15, 30, 123_456_000),
            ),
            charge,
        )
        assertEquals(sample, Json.encodeToString(charge))
    }

    @Test
    fun `money crosses the journal as a string with its scale, times with six fractional digits`() {
        val charge = Charge("id", BigDecimal("0.50"), "USD", "fp", LocalDateTime.of(2023, 8, 30, 15, 42, 17))

        val json = Json.encodeToString(charge)

        assertEquals("""{"chargeId":"id","fee":"0.50","asset":"USD","fingerprint":"fp","chargedAt":"2023-08-30T15:42:17.000000"}""", json)
        assertEquals(charge, Json.decodeFromString<Charge>(json))
    }

    @Test
    fun `a calculation journals its outcome with a type discriminator`() {
        val quoted: CalculationJournal =
            CalculationJournal.Quoted(QuoteJournal(BigDecimal("1.50"), BigDecimal("0.0015"), "Standard fee rate of 0.15%", "mobile-top-up", 1))
        val rejected: CalculationJournal = CalculationJournal.Rejected("type: unknown type")

        assertEquals(quoted, Json.decodeFromString<CalculationJournal>(Json.encodeToString(quoted)))
        assertEquals("""{"type":"rejected","detail":"type: unknown type"}""", Json.encodeToString(rejected))
    }

    @Test
    fun `an empty status and an empty lookup encode as empty objects`() {
        assertEquals("{}", Json.encodeToString(WorkflowStatus()))
        assertEquals(WorkflowStatus(), Json.decodeFromString<WorkflowStatus>("{}"))
        assertEquals(LookupJournal(), Json.decodeFromString<LookupJournal>("{}"))
    }

    @Test
    fun `every journal type pins its serial name, so a Kotlin rename never changes it`() {
        val pinned: Map<KSerializer<*>, String> = mapOf(
            FeeWorkflowInput.serializer() to "FeeWorkflowInput",
            LookupJournal.serializer() to "LookupJournal",
            QuoteJournal.serializer() to "QuoteJournal",
            CalculationJournal.serializer() to "CalculationJournal",
            CalculationJournal.Quoted.serializer() to "quoted",
            CalculationJournal.Rejected.serializer() to "rejected",
            ChargeRequest.serializer() to "ChargeRequest",
            Charge.serializer() to "Charge",
            RecordJournal.serializer() to "RecordJournal",
            Rejection.serializer() to "Rejection",
            WorkflowStatus.serializer() to "WorkflowStatus",
            FeeWorkflowOutput.serializer() to "FeeWorkflowOutput",
        )

        pinned.forEach { (serializer, name) -> assertEquals(name, serializer.descriptor.serialName) }
    }
}
